/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2016, FrostWire(R). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dkf.jmule.fragments;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.*;
import org.apache.commons.io.FilenameUtils;
import org.dkf.jed2k.alert.*;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.protocol.SearchEntry;
import org.dkf.jed2k.protocol.server.search.SearchRequest;
import org.dkf.jmule.AlertListener;
import org.dkf.jmule.ConfigurationManager;
import org.dkf.jmule.Constants;
import org.dkf.jmule.MediaType;
import org.dkf.jmule.Engine;
import org.dkf.jmule.R;
import org.dkf.jmule.adapters.SearchResultListAdapter;
import org.dkf.jmule.dialogs.NewTransferDialog;
import org.dkf.jmule.tasks.StartDownloadTask;
import org.dkf.jmule.tasks.Tasks;
import org.dkf.jmule.util.UIUtils;
import org.dkf.jmule.views.AbstractDialog.OnDialogClickListener;
import org.dkf.jmule.views.*;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * @author gubatron
 * @author aldenml
 */
public final class SearchFragment extends AbstractFragment implements
        MainFragment,
        OnDialogClickListener,
        SearchProgressView.CurrentQueryReporter,
        AlertListener {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(SearchFragment.class);
    private SearchResultListAdapter adapter = null;

    private SearchInputView searchInput;
    private ProgressBar deepSearchProgress;
    private RichNotification serverConnectionWarning;
    private RichNotification safeModeInfo;
    private SearchParametersView searchParametersView;
    private SearchProgressView searchProgress;
    ButtonSearchParametersListener buttonSearchParametersListener;
    private ListView list;
    private String currentQuery;
    private final FileTypeCounter fileTypeCounter;
    private final SparseArray<Byte> toTheRightOf = new SparseArray<>(9);
    private final SparseArray<Byte> toTheLeftOf = new SparseArray<>(9);

    private boolean awaitingResults = false;

    // Search timeout watchdog
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private static final long SEARCH_TIMEOUT_MS = 60_000;

    // sources (SearchResultAlert.SOURCE_*) still awaiting their final result packet
    private final Set<Integer> pendingSources = new HashSet<>();
    // increments on every new search / cancel / timeout so stale results are ignored
    private int searchGeneration = 0;

    private final Runnable searchTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pendingSources.isEmpty()) {
                log.info("search timeout after {}ms, sources still pending {}", SEARCH_TIMEOUT_MS, pendingSources);
                searchGeneration++;          // invalidate any results that still arrive
                pendingSources.clear();
                awaitingResults = false;
                searchProgress.setProgressEnabled(false);
                showSearchView(getView());
                if (getView() != null) {
                    UIUtils.showInformationDialog(getView().getContext(),
                            R.string.search_timed_out,
                            R.string.search_timed_out_title,
                            false, null);
                }
            }
        }
    };

    public SearchFragment() {
        super(R.layout.fragment_search);
        buttonSearchParametersListener = new ButtonSearchParametersListener(this);
        fileTypeCounter = new FileTypeCounter();
        currentQuery = null;

        toTheRightOf.put(Constants.FILE_TYPE_AUDIO, Constants.FILE_TYPE_VIDEOS);
        toTheRightOf.put(Constants.FILE_TYPE_VIDEOS, Constants.FILE_TYPE_PICTURES);
        toTheRightOf.put(Constants.FILE_TYPE_PICTURES, Constants.FILE_TYPE_APPLICATIONS);
        toTheRightOf.put(Constants.FILE_TYPE_APPLICATIONS, Constants.FILE_TYPE_DOCUMENTS);
        toTheRightOf.put(Constants.FILE_TYPE_DOCUMENTS, Constants.FILE_TYPE_ARCHIVE);
        toTheRightOf.put(Constants.FILE_TYPE_ARCHIVE, Constants.FILE_TYPE_CD_IMAGE);
        toTheRightOf.put(Constants.FILE_TYPE_CD_IMAGE, Constants.FILE_TYPE_TORRENTS);
        toTheRightOf.put(Constants.FILE_TYPE_TORRENTS, Constants.FILE_TYPE_OTHERS);
        toTheRightOf.put(Constants.FILE_TYPE_OTHERS, Constants.FILE_TYPE_AUDIO);

        toTheLeftOf.put(Constants.FILE_TYPE_AUDIO, Constants.FILE_TYPE_OTHERS);
        toTheLeftOf.put(Constants.FILE_TYPE_VIDEOS, Constants.FILE_TYPE_AUDIO);
        toTheLeftOf.put(Constants.FILE_TYPE_PICTURES, Constants.FILE_TYPE_VIDEOS);
        toTheLeftOf.put(Constants.FILE_TYPE_APPLICATIONS, Constants.FILE_TYPE_PICTURES);
        toTheLeftOf.put(Constants.FILE_TYPE_DOCUMENTS, Constants.FILE_TYPE_APPLICATIONS);
        toTheLeftOf.put(Constants.FILE_TYPE_ARCHIVE, Constants.FILE_TYPE_DOCUMENTS);
        toTheLeftOf.put(Constants.FILE_TYPE_CD_IMAGE, Constants.FILE_TYPE_ARCHIVE);
        toTheLeftOf.put(Constants.FILE_TYPE_TORRENTS, Constants.FILE_TYPE_CD_IMAGE);
        toTheLeftOf.put(Constants.FILE_TYPE_OTHERS, Constants.FILE_TYPE_TORRENTS);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setupAdapter();
        setRetainInstance(true);
    }

    @Override
    public View getHeader(Activity activity) {

        LayoutInflater inflater = LayoutInflater.from(activity);

        View header = inflater.inflate(R.layout.view_search_header, null);

        TextView text = (TextView) header.findViewById(R.id.view_search_header_text_title);
        text.setText(R.string.search);

        ImageButton buttonMenu = (ImageButton) header.findViewById(R.id.view_search_header_more_parameters);
        buttonMenu.setOnClickListener(buttonSearchParametersListener);
        return header;
    }

    @Override
    public void onResume() {
        super.onResume();
        Engine.instance().setListener(this);

        if (adapter != null && (adapter.getCount() > 0 || adapter.getTotalCount() > 0)) {
            refreshFileTypeCounters(true);
        }

        searchParametersView.showSearchSourceChooser(!Engine.instance().getCurrentServerId().isEmpty() && Engine.instance().isDhtEnabled());
    }

    @Override
    public void onPause() {
        super.onPause();
        Engine.instance().removeListener(this);
    }

    @Override
    public void onDestroy() {
        timeoutHandler.removeCallbacks(searchTimeoutRunnable);
        Engine.instance().removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onShow() {
        warnNoServerNoDhtConnections(getView());
        warnSafeMode();
        searchParametersView.showSearchSourceChooser(!Engine.instance().getCurrentServerId().isEmpty() && Engine.instance().isDhtEnabled());
    }

    @Override
    protected void initComponents(final View view) {
        searchInput = findView(view, R.id.fragment_search_input);
        searchInput.setShowKeyboardOnPaste(true);
        searchInput.setOnSearchListener(new SearchInputOnSearchListener((LinearLayout) view, this));

        deepSearchProgress = findView(view, R.id.fragment_search_deepsearch_progress);
        deepSearchProgress.setVisibility(View.GONE);

        serverConnectionWarning = findView(view, R.id.fragment_search_rating_reminder_notification);
        serverConnectionWarning.setVisibility(View.GONE);

        safeModeInfo = findView(view, R.id.fragment_search_safe_mode);
        safeModeInfo.setVisibility(View.GONE);

        searchParametersView = findView(view, R.id.fragment_search_parameters);
        searchParametersView.setVisibility(View.GONE);

        searchProgress = findView(view, R.id.fragment_search_search_progress);
        searchProgress.setCurrentQueryReporter(this);

        searchProgress.setCancelOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelSearch();
            }
        });

        list = findView(view, R.id.fragment_search_list);
        SwipeLayout swipe = findView(view, R.id.fragment_search_swipe);
        swipe.setOnSwipeListener(new SwipeLayout.OnSwipeListener() {
            @Override
            public void onSwipeLeft() {
                switchToThe(true);
            }

            @Override
            public void onSwipeRight() {
                switchToThe(false);
            }
        });

        showSearchView(view);
    }

    private void toggleSearchParametersView() {
        if (searchParametersView.getVisibility() == View.GONE) {
            searchParametersView.setVisibility(View.VISIBLE);
        } else {
            searchParametersView.setVisibility(View.GONE);
        }
    }

    private static final class ButtonSearchParametersListener extends ClickAdapter<SearchFragment> {

        ButtonSearchParametersListener(SearchFragment f) {
            super(f);
        }

        @Override
        public void onClick(SearchFragment f, View v) {
            f.toggleSearchParametersView();
        }
    }

    private void setupAdapter() {
        if (adapter == null) {
            adapter = new SearchResultListAdapter(getActivity()) {
                @Override
                protected void searchResultClicked(SearchEntry sr) {
                    log.info("start transfer {}", sr.getFileName());
                    startTransfer(sr, getString(R.string.download_added_to_queue));
                }
            };
        }
        list.setAdapter(adapter);
    }

    private void refreshFileTypeCounters(boolean fileTypeCountersVisible) {
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_APPLICATIONS, fileTypeCounter.numApplications);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_AUDIO, fileTypeCounter.numAudio);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_DOCUMENTS, fileTypeCounter.numDocuments);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_PICTURES, fileTypeCounter.numPictures);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_TORRENTS, fileTypeCounter.numTorrents);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_VIDEOS, fileTypeCounter.numVideo);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_ARCHIVE, fileTypeCounter.numArchive);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_CD_IMAGE, fileTypeCounter.numCDImage);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_OTHERS, fileTypeCounter.numOther);
        searchInput.setFileTypeCountersVisible(fileTypeCountersVisible);
    }

    private void performSearch(String query) {
        warnNoServerNoDhtConnections(getView());
        warnSafeMode();
        String expression = query.trim();
        if (expression.isEmpty()) return;

        // Cancel any pending search (stale guard)
        if (!pendingSources.isEmpty()) {
            log.info("cancel previous pending search (pending={}) before starting new", pendingSources);
            searchGeneration++;          // stale results of the old search are dropped
            pendingSources.clear();
            awaitingResults = false;
            timeoutHandler.removeCallbacks(searchTimeoutRunnable);
        }

        try {
            // Validate query before dispatching
            long minSize = searchParametersView.getMinSize() * 1024L * 1024L;
            long maxSize = searchParametersView.getMaxSize() * 1024L * 1024L;
            int sources = searchParametersView.getSourcesCount();
            int complete = searchParametersView.getCompleteSources();
            String fileType = searchParametersView.getChecked();
            String fileExtension = "";
            String codec = "";
            int mediaLength = 0;
            int mediaBitrate = 0;

            try {
                SearchRequest.validate(minSize, maxSize, sources, complete, fileType, fileExtension, codec, mediaLength, mediaBitrate, expression);
            } catch (JED2KException e) {
                log.error("search query validation failed: {}", e.getMessage());
                Context ctx = getActivity();
                if (ctx == null) ctx = getView() != null ? getView().getContext() : null;
                if (ctx != null) {
                    UIUtils.showInformationDialog(ctx,
                            R.string.search_failed_title,
                            R.string.search_failed_title,
                            false, null);
                }
                return;
            }

             if (!Engine.instance().isSafeMode() || !Engine.instance().isFiltered(expression)) {
                adapter.clear();
                fileTypeCounter.clear();
                refreshFileTypeCounters(false);
                currentQuery = query;

                boolean serverAvailable = !Engine.instance().getCurrentServerId().isEmpty();
                boolean dhtAvailable = Engine.instance().isDhtEnabled();
                boolean wantServer = serverAvailable && (searchParametersView.isSearchByServer() || !dhtAvailable);
                boolean wantDht = dhtAvailable && (!searchParametersView.isSearchByServer() || !serverAvailable);

                // When both server and DHT available, search both in parallel
                if (serverAvailable && dhtAvailable && searchParametersView.isSearchByServer()) {
                    wantServer = true;
                    wantDht = true;
                }

                pendingSources.clear();

                if (wantServer) {
                    log.info("perform search on servers");
                    pendingSources.add(SearchResultAlert.SOURCE_SERVER);
                    Engine.instance().performSearch(minSize, maxSize, sources, complete, fileType, fileExtension, codec, mediaLength, mediaBitrate, expression);
                }

                if (wantDht) {
                    log.info("perform search on KAD");
                    pendingSources.add(SearchResultAlert.SOURCE_KAD);
                    Engine.instance().performSearchDhtKeyword(expression, minSize, maxSize, sources, complete);
                }

                if (!pendingSources.isEmpty()) {
                    awaitingResults = true;
                    searchProgress.setProgressEnabled(true);
                    showSearchView(getView());
                    startSearchTimeout();
                }
            } else {
                 Context ctx = getActivity();
                 if (ctx == null) ctx = getView() != null ? getView().getContext() : null;
                 if (ctx != null) {
                     UIUtils.showInformationDialog(ctx
                             , R.string.search_forbidden
                             , R.string.search_forbidden_title
                             , false
                             , null);
                 }
             }
        } catch(NumberFormatException e) {
            log.error("Number format exception on input {}", e);
            Context ctx = getActivity();
            if (ctx == null) ctx = getView() != null ? getView().getContext() : null;
            if (ctx != null) {
                UIUtils.showInformationDialog(ctx
                        , R.string.search_params_invalid_number
                        , R.string.search_failed_title
                        , true
                        , null);
            }
        }
    }

    public void performSearchMore() {
        if (!Engine.instance().getCurrentServerId().isEmpty()) {
            if (!pendingSources.isEmpty()) {
                searchGeneration++;
                pendingSources.clear();
                awaitingResults = false;
                timeoutHandler.removeCallbacks(searchTimeoutRunnable);
                adapter.clear();
            }
            pendingSources.add(SearchResultAlert.SOURCE_SERVER);
            awaitingResults = true;
            adapter.clear();
            refreshFileTypeCounters(false);
            Engine.instance().performSearchMore();
            searchProgress.setProgressEnabled(true);
            showSearchView(getView());
            startSearchTimeout();
        }
    }

    public void removeEntry(SearchEntry searchEntry) {
        Engine.instance().blockHash(searchEntry.getHash());
        adapter.removeEntry(searchEntry);
    }

    private void cancelSearch() {
        log.info("cancel search pending={}", pendingSources);
        timeoutHandler.removeCallbacks(searchTimeoutRunnable);
        if (awaitingResults || !pendingSources.isEmpty()) {
            searchGeneration++;          // stale results are dropped
            pendingSources.clear();
            awaitingResults = false;
            adapter.clear();
            fileTypeCounter.clear();
            refreshFileTypeCounters(false);
            currentQuery = null;
            searchProgress.setProgressEnabled(false);
            showSearchView(getView());
        }
    }

    private void startSearchTimeout() {
        timeoutHandler.removeCallbacks(searchTimeoutRunnable);
        timeoutHandler.postDelayed(searchTimeoutRunnable, SEARCH_TIMEOUT_MS);
    }

    private void searchCompleted(final SearchResultAlert alert, final int generation) {
        // drop results belonging to a previous/cancelled search
        if (generation != searchGeneration) {
            log.debug("ignoring stale search results (generation {} != current {})", generation, searchGeneration);
            return;
        }

        if (alert.getResults() != null && !alert.getResults().isEmpty()) {
            adapter.addResults(alert.getResults(), alert.isHasMoreResults());

            for (SearchEntry entry : alert.getResults()) {
                fileTypeCounter.increment(MediaType.getMediaTypeForExtension(FilenameUtils.getExtension(entry.getFileName())));
            }
        }

        // a source is completed only by its final result packet
        if (!alert.isHasMoreResults()) {
            pendingSources.remove(alert.getSource());
            log.info("source {} finished, remaining {}", alert.getSource(), pendingSources);
        }

        // all sources completed -> search finished
        if (pendingSources.isEmpty()) {
            awaitingResults = false;
            timeoutHandler.removeCallbacks(searchTimeoutRunnable);

            refreshFileTypeCounters(true);
            searchProgress.setProgressEnabled(false);
            showSearchView(getView());
        }
    }

    private void showSearchView(View view) {
        if (awaitingResults || !pendingSources.isEmpty()) {
            switchView(view, R.id.fragment_search_search_progress);
        } else {
            switchView(view, R.id.fragment_search_list);
        }
    }

    private void switchView(View v, int id) {
        if (v != null) {
            FrameLayout frameLayout = findView(v, R.id.fragment_search_framelayout);
            int childCount = frameLayout.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View childAt = frameLayout.getChildAt(i);
                childAt.setVisibility((childAt.getId() == id) ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    @Override
    public void onDialogClick(String tag, int which) {
        if (tag.equals(NewTransferDialog.TAG) && which == Dialog.BUTTON_POSITIVE) {
            startDownload(this.getActivity(), NewTransferDialog.entry, getString(R.string.download_added_to_queue));
            NewTransferDialog.entry = null;
        }
    }

    private void startTransfer(final SearchEntry sr, final String toastMessage) {
        if (ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_GUI_SHOW_NEW_TRANSFER_DIALOG)) {
            try {
                NewTransferDialog dlg = NewTransferDialog.newInstance(sr, false);
                dlg.show(getFragmentManager());
            } catch (IllegalStateException e) {
                // android.app.FragmentManagerImpl.checkStateLoss:1323 -> java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState
                // just start the download then if the dialog crapped out.
                onDialogClick(NewTransferDialog.TAG, Dialog.BUTTON_POSITIVE);
            }
        } else {
            startDownload(getActivity(), sr, toastMessage);
        }
    }

    public static void startDownload(Context ctx, SearchEntry entry, String message) {
        StartDownloadTask task = new StartDownloadTask(ctx, entry, null, message);
        Tasks.executeParallel(task);
    }

    private void warnNoServerNoDhtConnections(View v) {
        if (Engine.instance().getCurrentServerId().isEmpty() && !Engine.instance().isDhtEnabled()) {
            serverConnectionWarning.setVisibility(View.VISIBLE);
        } else {
            serverConnectionWarning.setVisibility(View.GONE);
        }
    }

    private void warnSafeMode() {
        if (Engine.instance().isSafeMode() && !ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_GUI_ALERTED_SAFE_MODE)) {
            safeModeInfo.setVisibility(View.VISIBLE);
            ConfigurationManager.instance().setBoolean(Constants.PREF_KEY_GUI_ALERTED_SAFE_MODE, true);
        } else {
            safeModeInfo.setVisibility(View.GONE);
        }
    }

    @Override
    public String getCurrentQuery() {
        return currentQuery;
    }

    private void switchToThe(boolean right) {
        if (adapter == null) {
            return;
        }
        final byte currentFileType = (byte) adapter.getFileType();
        if (currentFileType != -1) { // SearchResultListAdapter#NO_FILE_TYPE (refactor this)
            final byte nextFileType = (right) ? toTheRightOf.get(currentFileType) : toTheLeftOf.get(currentFileType);
            searchInput.performClickOnRadioButton(nextFileType);
        }
    }

    @Override
    public void onListen(ListenAlert alert) {

    }

    @Override
    public void onSearchResult(final SearchResultAlert alert) {
        log.info("search result size {} more {} source {}", alert.getResults().size(), alert.isHasMoreResults()?"YES":"NO", alert.getSource());
        if (getActivity() == null) return;
        final int generation = searchGeneration;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                searchCompleted(alert, generation);
            }
        });
    }

    @Override
    public void onServerConnectionAlert(ServerConnectionAlert alert) {

    }

    @Override
    public void onServerMessage(ServerMessageAlert alert) {

    }

    @Override
    public void onServerStatus(ServerStatusAlert alert) {

    }

    @Override
    public void onServerIdAlert(ServerIdAlert alert) {

    }

    @Override
    public void onServerConnectionClosed(ServerConectionClosed alert) {

    }

    @Override
    public void onTransferAdded(TransferAddedAlert alert) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onTransferRemoved(TransferRemovedAlert alert) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onTransferPaused(TransferPausedAlert alert) {

    }

    @Override
    public void onTransferResumed(TransferResumedAlert alert) {

    }

    @Override
    public void onTransferIOError(TransferDiskIOErrorAlert alert) {

    }

    @Override
    public void onPortMapAlert(PortMapAlert alert) {

    }

    private static class SearchInputOnSearchListener implements SearchInputView.OnSearchListener {
        private final LinearLayout parentView;
        private final SearchFragment fragment;

        SearchInputOnSearchListener(LinearLayout parentView, SearchFragment fragment) {
            this.parentView = parentView;
            this.fragment = fragment;
        }

        public void onSearch(View v, String query, int mediaTypeId) {
            fragment.performSearch(query);
        }

        public void onMediaTypeSelected(View view, int mediaTypeId) {
            fragment.adapter.setFileType(mediaTypeId);
            fragment.showSearchView(parentView);
        }

        public void onClear(View v) {
            fragment.cancelSearch();
        }
    }

    private static final class FileTypeCounter {
        public int numAudio = 0;
        public int numVideo = 0;
        public int numPictures  = 0;
        public int numApplications  = 0;
        public int numDocuments = 0;
        public int numTorrents  = 0;
        public int numArchive = 0;
        public int numCDImage = 0;
        public int numOther = 0;

        private void increment(MediaType mt) {
            if (mt != null) {
                switch (mt.getId()) {
                    case Constants.FILE_TYPE_AUDIO:
                        numAudio++;
                        break;
                    case Constants.FILE_TYPE_VIDEOS:
                        numVideo++;
                        break;
                    case Constants.FILE_TYPE_PICTURES:
                        numPictures++;
                        break;
                    case Constants.FILE_TYPE_APPLICATIONS:
                        numApplications++;
                        break;
                    case Constants.FILE_TYPE_DOCUMENTS:
                        numDocuments++;
                        break;
                    case Constants.FILE_TYPE_TORRENTS:
                        numTorrents++;
                        break;
                    case Constants.FILE_TYPE_ARCHIVE:
                        numArchive++;
                        break;
                    case Constants.FILE_TYPE_CD_IMAGE:
                        numCDImage++;
                        break;
                    default:
                        numOther++;
                        break;
                }
            }
            else {
                numOther++;
            }
        }

        public void clear() {
            this.numAudio = 0;
            this.numApplications = 0;
            this.numDocuments = 0;
            this.numPictures = 0;
            this.numTorrents = 0;
            this.numVideo = 0;
            this.numArchive = 0;
            this.numCDImage = 0;
            this.numOther = 0;
        }
    }
}
