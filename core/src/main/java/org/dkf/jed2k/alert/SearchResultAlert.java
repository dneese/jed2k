package org.dkf.jed2k.alert;

import org.dkf.jed2k.protocol.SearchEntry;

import java.util.List;

/**
 * Created by inkpot on 24.07.2016.
 */
public class SearchResultAlert extends Alert {
    public static final int SOURCE_SERVER = 0;
    public static final int SOURCE_KAD = 1;

    private List<SearchEntry> results;
    private boolean hasMoreResults;
    private int source;

    public SearchResultAlert(final List<SearchEntry> results, boolean hasMoreResults) {
        this(results, hasMoreResults, SOURCE_SERVER);
    }

    public SearchResultAlert(final List<SearchEntry> results, boolean hasMoreResults, int source) {
        this.results = results;
        this.hasMoreResults = hasMoreResults;
        this.source = source;
    }

    @Override
    public Severity severity() {
        return Severity.Info;
    }

    @Override
    public int category() {
        return source == SOURCE_KAD ? Category.StatusNotification.value : Category.ServerNotification.value;
    }

    public List<SearchEntry> getResults() {
        return this.results;
    }

    public boolean isHasMoreResults() {
        return this.hasMoreResults;
    }

    public int getSource() {
        return this.source;
    }

    public String toString() {
        return "SearchResultAlert(results=" + this.getResults() + ", hasMoreResults=" + this.isHasMoreResults() + ", source=" + this.source + ")";
    }
}
