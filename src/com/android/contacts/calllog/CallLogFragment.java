/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts.calllog;

import com.android.common.io.MoreCloseables;
import com.android.common.widget.GroupingListAdapter;
import com.android.contacts.CallDetailActivity;
import com.android.contacts.ContactPhotoManager;
import com.android.contacts.ContactsUtils;
import com.android.contacts.PhoneCallDetails;
import com.android.contacts.PhoneCallDetailsHelper;
import com.android.contacts.R;
import com.android.contacts.activities.DialtactsActivity;
import com.android.contacts.activities.DialtactsActivity.ViewPagerVisibilityListener;
import com.android.contacts.util.ExpirableCache;
import com.android.internal.telephony.CallerInfo;
import com.google.common.annotations.VisibleForTesting;

import android.app.ListFragment;
import android.content.AsyncQueryHandler;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteDiskIOException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteFullException;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.CommonDataKinds.SipAddress;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ListView;
import android.widget.QuickContactBadge;

import java.lang.ref.WeakReference;
import java.util.LinkedList;

import javax.annotation.concurrent.GuardedBy;

/**
 * Displays a list of call log entries.
 */
public class CallLogFragment extends ListFragment implements ViewPagerVisibilityListener {
    private static final String TAG = "CallLogFragment";

    /** The size of the cache of contact info. */
    private static final int CONTACT_INFO_CACHE_SIZE = 100;

    /** The query for the call log table */
    public static final class CallLogQuery {
        public static final String[] _PROJECTION = new String[] {
                Calls._ID,
                Calls.NUMBER,
                Calls.DATE,
                Calls.DURATION,
                Calls.TYPE,
                Calls.COUNTRY_ISO,
        };
        public static final int ID = 0;
        public static final int NUMBER = 1;
        public static final int DATE = 2;
        public static final int DURATION = 3;
        public static final int CALL_TYPE = 4;
        public static final int COUNTRY_ISO = 5;

        /**
         * The name of the synthetic "section" column.
         * <p>
         * This column identifies whether a row is a header or an actual item, and whether it is
         * part of the new or old calls.
         */
        public static final String SECTION_NAME = "section";
        /** The index of the "section" column in the projection. */
        public static final int SECTION = 6;
        /** The value of the "section" column for the header of the new section. */
        public static final int SECTION_NEW_HEADER = 0;
        /** The value of the "section" column for the items of the new section. */
        public static final int SECTION_NEW_ITEM = 1;
        /** The value of the "section" column for the header of the old section. */
        public static final int SECTION_OLD_HEADER = 2;
        /** The value of the "section" column for the items of the old section. */
        public static final int SECTION_OLD_ITEM = 3;
    }

    /** The query to use for the phones table */
    private static final class PhoneQuery {
        public static final String[] _PROJECTION = new String[] {
                PhoneLookup._ID,
                PhoneLookup.DISPLAY_NAME,
                PhoneLookup.TYPE,
                PhoneLookup.LABEL,
                PhoneLookup.NUMBER,
                PhoneLookup.NORMALIZED_NUMBER,
                PhoneLookup.PHOTO_ID,
                PhoneLookup.LOOKUP_KEY};

        public static final int PERSON_ID = 0;
        public static final int NAME = 1;
        public static final int PHONE_TYPE = 2;
        public static final int LABEL = 3;
        public static final int MATCHED_NUMBER = 4;
        public static final int NORMALIZED_NUMBER = 5;
        public static final int PHOTO_ID = 6;
        public static final int LOOKUP_KEY = 7;
    }

    private static final class OptionsMenuItems {
        public static final int DELETE_ALL = 1;
    }

    private CallLogAdapter mAdapter;
    private QueryHandler mQueryHandler;
    private String mVoiceMailNumber;
    private String mCurrentCountryIso;
    private boolean mScrollToTop;

    private boolean mShowOptionsMenu;

    public static final class ContactInfo {
        public long personId;
        public String name;
        public int type;
        public String label;
        public String number;
        public String formattedNumber;
        public String normalizedNumber;
        public long photoId;
        public String lookupKey;

        public static ContactInfo EMPTY = new ContactInfo();
    }

    public static final class CallerInfoQuery {
        public String number;
        public int position;
        public String name;
        public int numberType;
        public String numberLabel;
        public long photoId;
        public String lookupKey;
    }

    /** Adapter class to fill in data for the Call Log */
    public final class CallLogAdapter extends GroupingListAdapter
            implements Runnable, ViewTreeObserver.OnPreDrawListener, View.OnClickListener {
        /** The time in millis to delay starting the thread processing requests. */
        private static final int START_PROCESSING_REQUESTS_DELAY_MILLIS = 1000;

        /**
         * A cache of the contact details for the phone numbers in the call log.
         * <p>
         * The content of the cache is expired (but not purged) whenever the application comes to
         * the foreground.
         */
        private ExpirableCache<String, ContactInfo> mContactInfoCache;

        /**
         * List of requests to update contact details.
         * <p>
         * The requests are added when displaying the contacts and are processed by a background
         * thread.
         */
        private final LinkedList<CallerInfoQuery> mRequests;

        private volatile boolean mDone;
        private boolean mLoading = true;
        private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
        private static final int REDRAW = 1;
        private static final int START_THREAD = 2;
        private boolean mFirst;
        private Thread mCallerIdThread;

        /** Instance of helper class for managing views. */
        private final CallLogListItemHelper mCallLogViewsHelper;

        /**
         * Reusable char array buffers.
         */
        private CharArrayBuffer mBuffer1 = new CharArrayBuffer(128);
        private CharArrayBuffer mBuffer2 = new CharArrayBuffer(128);
        /** Helper to set up contact photos. */
        private final ContactPhotoManager mContactPhotoManager;

        /** Can be set to true by tests to disable processing of requests. */
        private volatile boolean mRequestProcessingDisabled = false;

        @Override
        public void onClick(View view) {
            String number = (String) view.getTag();
            if (!TextUtils.isEmpty(number)) {
                // Here, "number" can either be a PSTN phone number or a
                // SIP address.  So turn it into either a tel: URI or a
                // sip: URI, as appropriate.
                Uri callUri;
                if (PhoneNumberUtils.isUriNumber(number)) {
                    callUri = Uri.fromParts("sip", number, null);
                } else {
                    callUri = Uri.fromParts("tel", number, null);
                }
                startActivity(new Intent(Intent.ACTION_CALL_PRIVILEGED, callUri));
            }
        }

        @Override
        public boolean onPreDraw() {
            if (mFirst) {
                mHandler.sendEmptyMessageDelayed(START_THREAD,
                        START_PROCESSING_REQUESTS_DELAY_MILLIS);
                mFirst = false;
            }
            return true;
        }

        private Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case REDRAW:
                        notifyDataSetChanged();
                        break;
                    case START_THREAD:
                        startRequestProcessing();
                        break;
                }
            }
        };

        public CallLogAdapter() {
            super(getActivity());

            mContactInfoCache = ExpirableCache.create(CONTACT_INFO_CACHE_SIZE);
            mRequests = new LinkedList<CallerInfoQuery>();
            mPreDrawListener = null;

            Resources resources = getResources();
            CallTypeHelper callTypeHelper = new CallTypeHelper(resources,
                    resources.getDrawable(R.drawable.ic_call_incoming_holo_dark),
                    resources.getDrawable(R.drawable.ic_call_outgoing_holo_dark),
                    resources.getDrawable(R.drawable.ic_call_missed_holo_dark),
                    resources.getDrawable(R.drawable.ic_call_voicemail_holo_dark));
            Drawable callDrawable = resources.getDrawable(
                    R.drawable.ic_call_log_list_action_call);
            Drawable playDrawable = resources.getDrawable(
                    R.drawable.ic_call_log_list_action_play);

            mContactPhotoManager = ContactPhotoManager.getInstance(getActivity());
            PhoneNumberHelper phoneNumberHelper =
                    new PhoneNumberHelper(getResources(), mVoiceMailNumber);
            PhoneCallDetailsHelper phoneCallDetailsHelper = new PhoneCallDetailsHelper(
                    getActivity(), resources, callTypeHelper, phoneNumberHelper );
            mCallLogViewsHelper = new CallLogListItemHelper(phoneCallDetailsHelper,
                    phoneNumberHelper, callDrawable, playDrawable);
        }

        /**
         * Requery on background thread when {@link Cursor} changes.
         */
        @Override
        protected void onContentChanged() {
            // Start async requery
            startQuery();
        }

        void setLoading(boolean loading) {
            mLoading = loading;
        }

        @Override
        public boolean isEmpty() {
            if (mLoading) {
                // We don't want the empty state to show when loading.
                return false;
            } else {
                return super.isEmpty();
            }
        }

        public ContactInfo getContactInfo(String number) {
            return mContactInfoCache.getPossiblyExpired(number);
        }

        public void startRequestProcessing() {
            if (mRequestProcessingDisabled) {
                return;
            }

            mDone = false;
            mCallerIdThread = new Thread(this);
            mCallerIdThread.setPriority(Thread.MIN_PRIORITY);
            mCallerIdThread.start();
        }

        /**
         * Stops the background thread that processes updates and cancels any pending requests to
         * start it.
         * <p>
         * Should be called from the main thread to prevent a race condition between the request to
         * start the thread being processed and stopping the thread.
         */
        public void stopRequestProcessing() {
            // Remove any pending requests to start the processing thread.
            mHandler.removeMessages(START_THREAD);
            mDone = true;
            if (mCallerIdThread != null) mCallerIdThread.interrupt();
        }

        public void invalidateCache() {
            mContactInfoCache.expireAll();
        }

        private void enqueueRequest(String number, boolean immediate, int position,
                String name, int numberType, String numberLabel, long photoId, String lookupKey) {
            CallerInfoQuery ciq = new CallerInfoQuery();
            ciq.number = number;
            ciq.position = position;
            ciq.name = name;
            ciq.numberType = numberType;
            ciq.numberLabel = numberLabel;
            ciq.photoId = photoId;
            ciq.lookupKey = lookupKey;
            synchronized (mRequests) {
                mRequests.add(ciq);
                mRequests.notifyAll();
            }
            if (mFirst && immediate) {
                startRequestProcessing();
                mFirst = false;
            }
        }

        private boolean queryContactInfo(CallerInfoQuery ciq) {
            // First check if there was a prior request for the same number
            // that was already satisfied
            ContactInfo info = mContactInfoCache.get(ciq.number);
            boolean needNotify = false;
            if (info != null && info != ContactInfo.EMPTY) {
                return true;
            } else {
                // Ok, do a fresh Contacts lookup for ciq.number.
                boolean infoUpdated = false;

                if (PhoneNumberUtils.isUriNumber(ciq.number)) {
                    // This "number" is really a SIP address.

                    // TODO: This code is duplicated from the
                    // CallerInfoAsyncQuery class.  To avoid that, could the
                    // code here just use CallerInfoAsyncQuery, rather than
                    // manually running ContentResolver.query() itself?

                    // We look up SIP addresses directly in the Data table:
                    Uri contactRef = Data.CONTENT_URI;

                    // Note Data.DATA1 and SipAddress.SIP_ADDRESS are equivalent.
                    //
                    // Also note we use "upper(data1)" in the WHERE clause, and
                    // uppercase the incoming SIP address, in order to do a
                    // case-insensitive match.
                    //
                    // TODO: May also need to normalize by adding "sip:" as a
                    // prefix, if we start storing SIP addresses that way in the
                    // database.
                    String selection = "upper(" + Data.DATA1 + ")=?"
                            + " AND "
                            + Data.MIMETYPE + "='" + SipAddress.CONTENT_ITEM_TYPE + "'";
                    String[] selectionArgs = new String[] { ciq.number.toUpperCase() };

                    Cursor dataTableCursor =
                            getActivity().getContentResolver().query(
                                    contactRef,
                                    null,  // projection
                                    selection,  // selection
                                    selectionArgs,  // selectionArgs
                                    null);  // sortOrder

                    if (dataTableCursor != null) {
                        if (dataTableCursor.moveToFirst()) {
                            info = new ContactInfo();

                            // TODO: we could slightly speed this up using an
                            // explicit projection (and thus not have to do
                            // those getColumnIndex() calls) but the benefit is
                            // very minimal.

                            // Note the Data.CONTACT_ID column here is
                            // equivalent to the PERSON_ID_COLUMN_INDEX column
                            // we use with "phonesCursor" below.
                            info.personId = dataTableCursor.getLong(
                                    dataTableCursor.getColumnIndex(Data.CONTACT_ID));
                            info.name = dataTableCursor.getString(
                                    dataTableCursor.getColumnIndex(Data.DISPLAY_NAME));
                            // "type" and "label" are currently unused for SIP addresses
                            info.type = SipAddress.TYPE_OTHER;
                            info.label = null;

                            // And "number" is the SIP address.
                            // Note Data.DATA1 and SipAddress.SIP_ADDRESS are equivalent.
                            info.number = dataTableCursor.getString(
                                    dataTableCursor.getColumnIndex(Data.DATA1));
                            info.normalizedNumber = null;  // meaningless for SIP addresses
                            info.photoId = dataTableCursor.getLong(
                                    dataTableCursor.getColumnIndex(Data.PHOTO_ID));
                            info.lookupKey = dataTableCursor.getString(
                                    dataTableCursor.getColumnIndex(Data.LOOKUP_KEY));

                            infoUpdated = true;
                        }
                        dataTableCursor.close();
                    }
                } else {
                    // "number" is a regular phone number, so use the
                    // PhoneLookup table:
                    Cursor phonesCursor =
                            getActivity().getContentResolver().query(
                                Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                                        Uri.encode(ciq.number)),
                                        PhoneQuery._PROJECTION, null, null, null);
                    if (phonesCursor != null) {
                        if (phonesCursor.moveToFirst()) {
                            info = new ContactInfo();
                            info.personId = phonesCursor.getLong(PhoneQuery.PERSON_ID);
                            info.name = phonesCursor.getString(PhoneQuery.NAME);
                            info.type = phonesCursor.getInt(PhoneQuery.PHONE_TYPE);
                            info.label = phonesCursor.getString(PhoneQuery.LABEL);
                            info.number = phonesCursor
                                    .getString(PhoneQuery.MATCHED_NUMBER);
                            info.normalizedNumber = phonesCursor
                                    .getString(PhoneQuery.NORMALIZED_NUMBER);
                            info.photoId = phonesCursor.getLong(PhoneQuery.PHOTO_ID);
                            info.lookupKey = phonesCursor.getString(PhoneQuery.LOOKUP_KEY);

                            infoUpdated = true;
                        }
                        phonesCursor.close();
                    }
                }

                if (infoUpdated) {
                    // New incoming phone number invalidates our formatted
                    // cache. Any cache fills happen only on the GUI thread.
                    info.formattedNumber = null;

                    mContactInfoCache.put(ciq.number, info);

                    // Inform list to update this item, if in view
                    needNotify = true;
                }
            }
            return needNotify;
        }

        /*
         * Handles requests for contact name and number type
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            boolean needNotify = false;
            while (!mDone) {
                CallerInfoQuery ciq = null;
                synchronized (mRequests) {
                    if (!mRequests.isEmpty()) {
                        ciq = mRequests.removeFirst();
                    } else {
                        if (needNotify) {
                            needNotify = false;
                            mHandler.sendEmptyMessage(REDRAW);
                        }
                        try {
                            mRequests.wait(1000);
                        } catch (InterruptedException ie) {
                            // Ignore and continue processing requests
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                if (!mDone && ciq != null && queryContactInfo(ciq)) {
                    needNotify = true;
                }
            }
        }

        @Override
        protected void addGroups(Cursor cursor) {
            int count = cursor.getCount();
            if (count == 0) {
                return;
            }

            int groupItemCount = 1;

            CharArrayBuffer currentValue = mBuffer1;
            CharArrayBuffer value = mBuffer2;
            cursor.moveToFirst();
            cursor.copyStringToBuffer(CallLogQuery.NUMBER, currentValue);
            int currentCallType = cursor.getInt(CallLogQuery.CALL_TYPE);
            for (int i = 1; i < count; i++) {
                cursor.moveToNext();
                cursor.copyStringToBuffer(CallLogQuery.NUMBER, value);
                boolean sameNumber = equalPhoneNumbers(value, currentValue);

                // Group adjacent calls with the same number. Make an exception
                // for the latest item if it was a missed call.  We don't want
                // a missed call to be hidden inside a group.
                if (sameNumber && currentCallType != Calls.MISSED_TYPE
                        && !isSectionHeader(cursor)) {
                    groupItemCount++;
                } else {
                    if (groupItemCount > 1) {
                        addGroup(i - groupItemCount, groupItemCount, false);
                    }

                    groupItemCount = 1;

                    // Swap buffers
                    CharArrayBuffer temp = currentValue;
                    currentValue = value;
                    value = temp;

                    // If we have just examined a row following a missed call, make
                    // sure that it is grouped with subsequent calls from the same number
                    // even if it was also missed.
                    if (sameNumber && currentCallType == Calls.MISSED_TYPE) {
                        currentCallType = 0;       // "not a missed call"
                    } else {
                        currentCallType = cursor.getInt(CallLogQuery.CALL_TYPE);
                    }
                }
            }
            if (groupItemCount > 1) {
                addGroup(count - groupItemCount, groupItemCount, false);
            }
        }

        private boolean isSectionHeader(Cursor cursor) {
            int section = cursor.getInt(CallLogQuery.SECTION);
            return section == CallLogQuery.SECTION_NEW_HEADER
                    || section == CallLogQuery.SECTION_OLD_HEADER;
        }

        private boolean isNewSection(Cursor cursor) {
            int section = cursor.getInt(CallLogQuery.SECTION);
            return section == CallLogQuery.SECTION_NEW_ITEM
                    || section == CallLogQuery.SECTION_NEW_HEADER;
        }

        protected boolean equalPhoneNumbers(CharArrayBuffer buffer1, CharArrayBuffer buffer2) {

            // TODO add PhoneNumberUtils.compare(CharSequence, CharSequence) to avoid
            // string allocation
            return PhoneNumberUtils.compare(new String(buffer1.data, 0, buffer1.sizeCopied),
                    new String(buffer2.data, 0, buffer2.sizeCopied));
        }


        @VisibleForTesting
        @Override
        public View newStandAloneView(Context context, ViewGroup parent) {
            LayoutInflater inflater =
                    (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View view = inflater.inflate(R.layout.call_log_list_item, parent, false);
            findAndCacheViews(view);
            return view;
        }

        @VisibleForTesting
        @Override
        public void bindStandAloneView(View view, Context context, Cursor cursor) {
            bindView(view, cursor, 1);
        }

        @VisibleForTesting
        @Override
        public View newChildView(Context context, ViewGroup parent) {
            LayoutInflater inflater =
                    (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View view = inflater.inflate(R.layout.call_log_list_child_item, parent, false);
            findAndCacheViews(view);
            return view;
        }

        @VisibleForTesting
        @Override
        public void bindChildView(View view, Context context, Cursor cursor) {
            bindView(view, cursor, 1);
        }

        @VisibleForTesting
        @Override
        public View newGroupView(Context context, ViewGroup parent) {
            LayoutInflater inflater =
                    (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View view = inflater.inflate(R.layout.call_log_list_group_item, parent, false);
            findAndCacheViews(view);
            return view;
        }

        @VisibleForTesting
        @Override
        public void bindGroupView(View view, Context context, Cursor cursor, int groupSize,
                boolean expanded) {
            bindView(view, cursor, groupSize);
        }

        private void findAndCacheViews(View view) {
            // Get the views to bind to.
            CallLogListItemViews views = CallLogListItemViews.fromView(view);
            if (views.callView != null) {
                views.callView.setOnClickListener(this);
            }
            view.setTag(views);
        }

        /**
         * Binds the views in the entry to the data in the call log.
         *
         * @param view the view corresponding to this entry
         * @param c the cursor pointing to the entry in the call log
         * @param count the number of entries in the current item, greater than 1 if it is a group
         */
        private void bindView(View view, Cursor c, int count) {
            final CallLogListItemViews views = (CallLogListItemViews) view.getTag();
            final int section = c.getInt(CallLogQuery.SECTION);

            if (views.standAloneItemView != null) {
                // This is stand-alone item: it might, however, be a header: check the value of the
                // section column in the cursor.
                if (section == CallLogQuery.SECTION_NEW_HEADER
                        || section == CallLogQuery.SECTION_OLD_HEADER) {
                    views.standAloneItemView.setVisibility(View.GONE);
                    views.standAloneHeaderView.setVisibility(View.VISIBLE);
                    views.standAloneHeaderTextView.setText(
                            section == CallLogQuery.SECTION_NEW_HEADER
                                    ? R.string.call_log_new_header
                                    : R.string.call_log_old_header);
                    // Nothing else to set up for a header.
                    return;
                }
                // Default case: an item in the call log.
                views.standAloneItemView.setVisibility(View.VISIBLE);
                views.standAloneHeaderView.setVisibility(View.GONE);
            }

            final String number = c.getString(CallLogQuery.NUMBER);
            final long date = c.getLong(CallLogQuery.DATE);
            final long duration = c.getLong(CallLogQuery.DURATION);
            final String formattedNumber;
            final String countryIso = c.getString(CallLogQuery.COUNTRY_ISO);
            // Store away the number so we can call it directly if you click on the call icon
            if (views.callView != null) {
                views.callView.setTag(number);
            }

            // Lookup contacts with this number
            ExpirableCache.CachedValue<ContactInfo> cachedInfo =
                    mContactInfoCache.getCachedValue(number);
            ContactInfo info = cachedInfo == null ? null : cachedInfo.getValue();
            if (cachedInfo == null) {
                // Mark it as empty and queue up a request to find the name
                // The db request should happen on a non-UI thread
                info = ContactInfo.EMPTY;
                // Format the cached call_log phone number
                formattedNumber = formatPhoneNumber(number, null, countryIso);
                mContactInfoCache.put(number, info);
                Log.d(TAG, "Contact info missing: " + number);
                // Request the contact details immediately since they are currently missing.
                enqueueRequest(number, true, c.getPosition(), "", 0, "", 0L, "");
            } else if (info != ContactInfo.EMPTY) { // Has been queried
                if (cachedInfo.isExpired()) {
                    Log.d(TAG, "Contact info expired: " + number);
                    // Put it back in the cache, therefore marking it as not expired, so that other
                    // entries with the same number will not re-request it.
                    mContactInfoCache.put(number, info);
                    // The contact info is no longer up to date, we should request it. However, we
                    // do not need to request them immediately.
                    enqueueRequest(number, false, c.getPosition(), info.name, info.type, info.label,
                            info.photoId, info.lookupKey);
                }

                // Format and cache phone number for found contact
                if (info.formattedNumber == null) {
                    info.formattedNumber =
                            formatPhoneNumber(info.number, info.normalizedNumber, countryIso);
                }
                formattedNumber = info.formattedNumber;
            } else {
                // Format the cached call_log phone number
                formattedNumber = formatPhoneNumber(number, null, countryIso);
            }

            final long personId = info.personId;
            final String name = info.name;
            final int ntype = info.type;
            final String label = info.label;
            final long photoId = info.photoId;
            final String lookupKey = info.lookupKey;
            // Assumes the call back feature is on most of the
            // time. For private and unknown numbers: hide it.
            if (views.callView != null) {
                views.callView.setVisibility(View.VISIBLE);
            }

            final int[] callTypes = getCallTypes(c, count);
            final PhoneCallDetails details;
            if (TextUtils.isEmpty(name)) {
                details = new PhoneCallDetails(number, formattedNumber, callTypes, date, duration);
            } else {
                details = new PhoneCallDetails(number, formattedNumber, callTypes, date, duration,
                        name, ntype, label, personId, photoId);
            }

            final boolean isNew = isNewSection(c);
            // Use icons for old items, but text for new ones.
            final boolean useIcons = !isNew;
            // New items also use the highlighted version of the text.
            final boolean isHighlighted = isNew;
            mCallLogViewsHelper.setPhoneCallDetails(views, details, useIcons, isHighlighted);
            if (views.photoView != null) {
                bindQuickContact(views.photoView, photoId, personId, lookupKey);
            }


            // Listen for the first draw
            if (mPreDrawListener == null) {
                mFirst = true;
                mPreDrawListener = this;
                view.getViewTreeObserver().addOnPreDrawListener(this);
            }
        }

        /**
         * Returns the call types for the given number of items in the cursor.
         * <p>
         * It uses the next {@code count} rows in the cursor to extract the types.
         * <p>
         * It position in the cursor is unchanged by this function.
         */
        private int[] getCallTypes(Cursor cursor, int count) {
            int position = cursor.getPosition();
            int[] callTypes = new int[count];
            for (int index = 0; index < count; ++index) {
                callTypes[index] = cursor.getInt(CallLogQuery.CALL_TYPE);
                cursor.moveToNext();
            }
            cursor.moveToPosition(position);
            return callTypes;
        }

        private void bindQuickContact(QuickContactBadge view, long photoId, long contactId,
                String lookupKey) {
            view.assignContactUri(getContactUri(contactId, lookupKey));
            mContactPhotoManager.loadPhoto(view, photoId);
        }

        private Uri getContactUri(long contactId, String lookupKey) {
            return Contacts.getLookupUri(contactId, lookupKey);
        }

        /**
         * Sets whether processing of requests for contact details should be enabled.
         * <p>
         * This method should be called in tests to disable such processing of requests when not
         * needed.
         */
        public void disableRequestProcessingForTest() {
            mRequestProcessingDisabled = true;
        }

        public void injectContactInfoForTest(String number, ContactInfo contactInfo) {
            mContactInfoCache.put(number, contactInfo);
        }
    }

    /** Handles asynchronous queries to the call log. */
    private static final class QueryHandler extends AsyncQueryHandler {
        /** The token for the query to fetch the new entries from the call log. */
        private static final int QUERY_NEW_CALLS_TOKEN = 53;
        /** The token for the query to fetch the old entries from the call log. */
        private static final int QUERY_OLD_CALLS_TOKEN = 54;
        /** The token for the query to mark all missed calls as old after seeing the call log. */
        private static final int UPDATE_MISSED_CALLS_TOKEN = 55;

        private final WeakReference<CallLogFragment> mFragment;

        /** The cursor containing the new calls, or null if they have not yet been fetched. */
        @GuardedBy("this") private Cursor mNewCallsCursor;
        /** The cursor containing the old calls, or null if they have not yet been fetched. */
        @GuardedBy("this") private Cursor mOldCallsCursor;

        /**
         * Simple handler that wraps background calls to catch
         * {@link SQLiteException}, such as when the disk is full.
         */
        protected class CatchingWorkerHandler extends AsyncQueryHandler.WorkerHandler {
            public CatchingWorkerHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                try {
                    // Perform same query while catching any exceptions
                    super.handleMessage(msg);
                } catch (SQLiteDiskIOException e) {
                    Log.w(TAG, "Exception on background worker thread", e);
                } catch (SQLiteFullException e) {
                    Log.w(TAG, "Exception on background worker thread", e);
                } catch (SQLiteDatabaseCorruptException e) {
                    Log.w(TAG, "Exception on background worker thread", e);
                }
            }
        }

        @Override
        protected Handler createHandler(Looper looper) {
            // Provide our special handler that catches exceptions
            return new CatchingWorkerHandler(looper);
        }

        public QueryHandler(CallLogFragment fragment) {
            super(fragment.getActivity().getContentResolver());
            mFragment = new WeakReference<CallLogFragment>(fragment);
        }

        /** Returns the list of columns for the headers. */
        private String[] getHeaderColumns() {
            int length = CallLogQuery._PROJECTION.length;
            String[] columns = new String[length + 1];
            System.arraycopy(CallLogQuery._PROJECTION, 0, columns, 0, length);
            columns[length] = CallLogQuery.SECTION_NAME;
            return columns;
        }

        /** Creates a cursor that contains a single row and maps the section to the given value. */
        private Cursor createHeaderCursorFor(int section) {
            MatrixCursor matrixCursor = new MatrixCursor(getHeaderColumns());
            matrixCursor.addRow(new Object[]{ -1L, "", 0L, 0L, 0, "", section });
            return matrixCursor;
        }

        /** Returns a cursor for the old calls header. */
        private Cursor createOldCallsHeaderCursor() {
            return createHeaderCursorFor(CallLogQuery.SECTION_OLD_HEADER);
        }

        /** Returns a cursor for the new calls header. */
        private Cursor createNewCallsHeaderCursor() {
            return createHeaderCursorFor(CallLogQuery.SECTION_NEW_HEADER);
        }

        /**
         * Fetches the list of calls from the call log.
         * <p>
         * It will asynchronously update the content of the list view when the fetch completes.
         */
        public void fetchCalls() {
            cancelFetch();
            invalidate();
            fetchNewCalls();
            fetchOldCalls();
        }

        /** Fetches the list of new calls in the call log. */
        private void fetchNewCalls() {
            fetchCalls(QUERY_NEW_CALLS_TOKEN, true);
        }

        /** Fetch the list of old calls in the call log. */
        private void fetchOldCalls() {
            fetchCalls(QUERY_OLD_CALLS_TOKEN, false);
        }

        /** Fetches the list of calls in the call log, either the new one or the old ones. */
        private void fetchCalls(int token, boolean isNew) {
            String selection =
                    String.format("%s = 1 AND (%s = ? OR %s = ?)",
                            Calls.NEW, Calls.TYPE, Calls.TYPE);
            String[] selectionArgs = new String[]{
                    Integer.toString(Calls.MISSED_TYPE),
                    Integer.toString(Calls.VOICEMAIL_TYPE),
            };
            if (!isNew) {
                selection = String.format("NOT (%s)", selection);
            }
            startQuery(token, null, Calls.CONTENT_URI_WITH_VOICEMAIL,
                    CallLogQuery._PROJECTION, selection, selectionArgs, Calls.DEFAULT_SORT_ORDER);
        }

        /** Cancel any pending fetch request. */
        private void cancelFetch() {
            cancelOperation(QUERY_NEW_CALLS_TOKEN);
            cancelOperation(QUERY_OLD_CALLS_TOKEN);
        }

        /** Updates the missed calls to mark them as old. */
        public void updateMissedCalls() {
            // Mark all "new" missed calls as not new anymore
            StringBuilder where = new StringBuilder();
            where.append("type = ");
            where.append(Calls.MISSED_TYPE);
            where.append(" AND ");
            where.append(Calls.NEW);
            where.append(" = 1");

            ContentValues values = new ContentValues(1);
            values.put(Calls.NEW, "0");

            startUpdate(UPDATE_MISSED_CALLS_TOKEN, null, Calls.CONTENT_URI_WITH_VOICEMAIL,
                    values, where.toString(), null);
        }

        /**
         * Invalidate the current list of calls.
         * <p>
         * This method is synchronized because it must close the cursors and reset them atomically.
         */
        private synchronized void invalidate() {
            MoreCloseables.closeQuietly(mNewCallsCursor);
            MoreCloseables.closeQuietly(mOldCallsCursor);
            mNewCallsCursor = null;
            mOldCallsCursor = null;
        }

        @Override
        protected synchronized void onQueryComplete(int token, Object cookie, Cursor cursor) {
            if (token == QUERY_NEW_CALLS_TOKEN) {
                // Store the returned cursor.
                mNewCallsCursor = new ExtendedCursor(
                        cursor, CallLogQuery.SECTION_NAME, CallLogQuery.SECTION_NEW_ITEM);
            } else if (token == QUERY_OLD_CALLS_TOKEN) {
                // Store the returned cursor.
                mOldCallsCursor = new ExtendedCursor(
                        cursor, CallLogQuery.SECTION_NAME, CallLogQuery.SECTION_OLD_ITEM);
            } else {
                Log.w(TAG, "Unknown query completed: ignoring: " + token);
                return;
            }

            if (mNewCallsCursor != null && mOldCallsCursor != null) {
                updateAdapterData(createMergedCursor());
            }
        }

        /** Creates the merged cursor representing the data to show in the call log. */
        @GuardedBy("this")
        private Cursor createMergedCursor() {
            try {
                final boolean noNewCalls = mNewCallsCursor.getCount() == 0;
                final boolean noOldCalls = mOldCallsCursor.getCount() == 0;

                if (noNewCalls && noOldCalls) {
                    // Nothing in either cursors.
                    MoreCloseables.closeQuietly(mNewCallsCursor);
                    return mOldCallsCursor;
                }

                if (noNewCalls) {
                    // Return only the old calls.
                    MoreCloseables.closeQuietly(mNewCallsCursor);
                    return new MergeCursor(
                            new Cursor[]{ createOldCallsHeaderCursor(), mOldCallsCursor });
                }

                if (noOldCalls) {
                    // Return only the new calls.
                    MoreCloseables.closeQuietly(mOldCallsCursor);
                    return new MergeCursor(
                            new Cursor[]{ createNewCallsHeaderCursor(), mNewCallsCursor });
                }

                return new MergeCursor(new Cursor[]{
                        createNewCallsHeaderCursor(), mNewCallsCursor,
                        createOldCallsHeaderCursor(), mOldCallsCursor});
            } finally {
                // Any cursor still open is now owned, directly or indirectly, by the caller.
                mNewCallsCursor = null;
                mOldCallsCursor = null;
            }
        }

        /**
         * Updates the adapter in the call log fragment to show the new cursor data.
         */
        private void updateAdapterData(Cursor combinedCursor) {
            final CallLogFragment fragment = mFragment.get();
            if (fragment != null && fragment.getActivity() != null &&
                    !fragment.getActivity().isFinishing()) {
                Log.d(TAG, "updating adapter");
                final CallLogAdapter callsAdapter = fragment.mAdapter;
                callsAdapter.setLoading(false);
                callsAdapter.changeCursor(combinedCursor);
                if (fragment.mScrollToTop) {
                    final ListView listView = fragment.getListView();
                    if (listView.getFirstVisiblePosition() > 5) {
                        listView.setSelection(5);
                    }
                    listView.smoothScrollToPosition(0);
                    fragment.mScrollToTop = false;
                }
            }
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        mVoiceMailNumber = ((TelephonyManager) getActivity().getSystemService(
                Context.TELEPHONY_SERVICE)).getVoiceMailNumber();
        mQueryHandler = new QueryHandler(this);

        mCurrentCountryIso = ContactsUtils.getCurrentCountryIso(getActivity());

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        return inflater.inflate(R.layout.call_log_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAdapter = new CallLogAdapter();
        setListAdapter(mAdapter);
    }

    @Override
    public void onStart() {
        mScrollToTop = true;
        super.onStart();
    }

    @Override
    public void onResume() {
        // Mark all entries in the contact info cache as out of date, so they will be looked up
        // again once being shown.
        if (mAdapter != null) {
            mAdapter.invalidateCache();
        }

        startQuery();
        resetNewCallsFlag();

        super.onResume();

        mAdapter.mPreDrawListener = null; // Let it restart the thread after next draw
    }

    @Override
    public void onPause() {
        super.onPause();

        // Kill the requests thread
        mAdapter.stopRequestProcessing();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mAdapter.stopRequestProcessing();
        mAdapter.changeCursor(null);
    }

    /**
     * Format the given phone number
     *
     * @param number the number to be formatted.
     * @param normalizedNumber the normalized number of the given number.
     * @param countryIso the ISO 3166-1 two letters country code, the country's
     *        convention will be used to format the number if the normalized
     *        phone is null.
     *
     * @return the formatted number, or the given number if it was formatted.
     */
    private String formatPhoneNumber(String number, String normalizedNumber, String countryIso) {
        if (TextUtils.isEmpty(number)) {
            return "";
        }
        // If "number" is really a SIP address, don't try to do any formatting at all.
        if (PhoneNumberUtils.isUriNumber(number)) {
            return number;
        }
        if (TextUtils.isEmpty(countryIso)) {
            countryIso = mCurrentCountryIso;
        }
        return PhoneNumberUtils.formatNumber(number, normalizedNumber, countryIso);
    }

    private void resetNewCallsFlag() {
        mQueryHandler.updateMissedCalls();
    }

    private void startQuery() {
        mAdapter.setLoading(true);
        mQueryHandler.fetchCalls();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.call_log_options, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.delete_all).setVisible(mShowOptionsMenu);
        final MenuItem callSettingsMenuItem = menu.findItem(R.id.menu_call_settings_call_log);
        if (mShowOptionsMenu) {
            callSettingsMenuItem.setVisible(true);
            callSettingsMenuItem.setIntent(DialtactsActivity.getCallSettingsIntent());
        } else {
            callSettingsMenuItem.setVisible(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case OptionsMenuItems.DELETE_ALL: {
                ClearCallLogDialog.show(getFragmentManager());
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    /*
     * Get the number from the Contacts, if available, since sometimes
     * the number provided by caller id may not be formatted properly
     * depending on the carrier (roaming) in use at the time of the
     * incoming call.
     * Logic : If the caller-id number starts with a "+", use it
     *         Else if the number in the contacts starts with a "+", use that one
     *         Else if the number in the contacts is longer, use that one
     */
    private String getBetterNumberFromContacts(String number) {
        String matchingNumber = null;
        // Look in the cache first. If it's not found then query the Phones db
        ContactInfo ci = mAdapter.mContactInfoCache.getPossiblyExpired(number);
        if (ci != null && ci != ContactInfo.EMPTY) {
            matchingNumber = ci.number;
        } else {
            try {
                Cursor phonesCursor = getActivity().getContentResolver().query(
                        Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, number),
                        PhoneQuery._PROJECTION, null, null, null);
                if (phonesCursor != null) {
                    if (phonesCursor.moveToFirst()) {
                        matchingNumber = phonesCursor.getString(PhoneQuery.MATCHED_NUMBER);
                    }
                    phonesCursor.close();
                }
            } catch (Exception e) {
                // Use the number from the call log
            }
        }
        if (!TextUtils.isEmpty(matchingNumber) &&
                (matchingNumber.startsWith("+")
                        || matchingNumber.length() > number.length())) {
            number = matchingNumber;
        }
        return number;
    }

    public void callSelectedEntry() {
        int position = getListView().getSelectedItemPosition();
        if (position < 0) {
            // In touch mode you may often not have something selected, so
            // just call the first entry to make sure that [send] [send] calls the
            // most recent entry.
            position = 0;
        }
        final Cursor cursor = (Cursor)mAdapter.getItem(position);
        if (cursor != null) {
            String number = cursor.getString(CallLogQuery.NUMBER);
            if (TextUtils.isEmpty(number)
                    || number.equals(CallerInfo.UNKNOWN_NUMBER)
                    || number.equals(CallerInfo.PRIVATE_NUMBER)
                    || number.equals(CallerInfo.PAYPHONE_NUMBER)) {
                // This number can't be called, do nothing
                return;
            }
            Intent intent;
            // If "number" is really a SIP address, construct a sip: URI.
            if (PhoneNumberUtils.isUriNumber(number)) {
                intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                    Uri.fromParts("sip", number, null));
            } else {
                // We're calling a regular PSTN phone number.
                // Construct a tel: URI, but do some other possible cleanup first.
                int callType = cursor.getInt(CallLogQuery.CALL_TYPE);
                if (!number.startsWith("+") &&
                       (callType == Calls.INCOMING_TYPE
                                || callType == Calls.MISSED_TYPE)) {
                    // If the caller-id matches a contact with a better qualified number, use it
                    number = getBetterNumberFromContacts(number);
                }
                intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                    Uri.fromParts("tel", number, null));
            }
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
        }
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = new Intent(getActivity(), CallDetailActivity.class);
        if (mAdapter.isGroupHeader(position)) {
            int groupSize = mAdapter.getGroupSize(position);
            long[] ids = new long[groupSize];
            // Copy the ids of the rows in the group.
            Cursor cursor = (Cursor) mAdapter.getItem(position);
            // Restore the position in the cursor at the end.
            int currentPosition = cursor.getPosition();
            for (int index = 0; index < groupSize; ++index) {
                ids[index] = cursor.getLong(CallLogQuery.ID);
                cursor.moveToNext();
            }
            cursor.moveToPosition(currentPosition);
            intent.putExtra(CallDetailActivity.EXTRA_CALL_LOG_IDS, ids);
        } else {
            // If there is a single item, use the direct URI for it.
            intent.setData(ContentUris.withAppendedId(Calls.CONTENT_URI_WITH_VOICEMAIL, id));
        }
        startActivity(intent);
    }

    @VisibleForTesting
    public CallLogAdapter getAdapter() {
        return mAdapter;
    }

    @VisibleForTesting
    public String getVoiceMailNumber() {
        return mVoiceMailNumber;
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mShowOptionsMenu = visible;
    }
}
