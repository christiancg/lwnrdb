package org.techhouse.analyze;

import java.util.List;

/**
 * Serialized payload for AGGREGATE analyze mode. A plain public-field DTO so the EJson reflection
 * serializer emits it like the other response DTOs. The timing fields
 * ({@code startTime}/{@code endTime}/{@code durationMillis}) are set by {@code MessageProcessor}
 * around the processing call; the remaining fields are populated by {@code AnalyzeHelper} from the
 * {@link AnalyzeContext} gathered during the pipeline run.
 */
public class AnalyzeResult {
    public long startTime;
    public long endTime;
    public long durationMillis;
    public boolean indexUsed;
    public List<String> indexesUsed;
    public long documentsScanned;
    public List<String> locksAcquired;
    public List<String> suggestions;

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public boolean isIndexUsed() {
        return indexUsed;
    }

    public void setIndexUsed(boolean indexUsed) {
        this.indexUsed = indexUsed;
    }

    public List<String> getIndexesUsed() {
        return indexesUsed;
    }

    public void setIndexesUsed(List<String> indexesUsed) {
        this.indexesUsed = indexesUsed;
    }

    public long getDocumentsScanned() {
        return documentsScanned;
    }

    public void setDocumentsScanned(long documentsScanned) {
        this.documentsScanned = documentsScanned;
    }

    public List<String> getLocksAcquired() {
        return locksAcquired;
    }

    public void setLocksAcquired(List<String> locksAcquired) {
        this.locksAcquired = locksAcquired;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }
}
