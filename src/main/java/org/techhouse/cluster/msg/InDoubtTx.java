package org.techhouse.cluster.msg;

import java.util.List;

public class InDoubtTx {
    private String dtxId;
    private String coordinator;
    private List<String> participants = List.of();
    private long preparedAt;
    private String status;

    public InDoubtTx() {
    }

    public InDoubtTx(String dtxId, String coordinator, List<String> participants, long preparedAt, String status) {
        this.dtxId = dtxId;
        this.coordinator = coordinator;
        this.participants = participants == null ? List.of() : participants;
        this.preparedAt = preparedAt;
        this.status = status;
    }

    public String getDtxId() {
        return dtxId;
    }

    public void setDtxId(String dtxId) {
        this.dtxId = dtxId;
    }

    public String getCoordinator() {
        return coordinator;
    }

    public void setCoordinator(String coordinator) {
        this.coordinator = coordinator;
    }

    public List<String> getParticipants() {
        return participants;
    }

    public void setParticipants(List<String> participants) {
        this.participants = participants == null ? List.of() : participants;
    }

    public long getPreparedAt() {
        return preparedAt;
    }

    public void setPreparedAt(long preparedAt) {
        this.preparedAt = preparedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
