package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.error.NotFoundException;
import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.Descriptors;
import io.github.christiancg.lwnrdb.client.internal.PipelineStep;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.query.Filter;
import java.util.List;
import java.util.Optional;

public final class Users {
    private final ClientContext context;

    Users(ClientContext context) {
        this.context = context;
    }

    public void create(String username, String password) {
        create(username, password, Optional.empty());
    }

    public void create(String username, String password, Permissions permissions) {
        create(username, password, Optional.of(permissions));
    }

    public void delete(String username) {
        context.channel().send(Operation.DELETE_USER, context.scripts().deleteUser(username));
    }

    public void changePermissions(String username, Permissions permissions) {
        context.channel().send(Operation.CHANGE_PERMISSIONS,
                context.scripts().changePermissions(username, permissions));
    }

    public void setPassword(String username, String newPassword) {
        context.channel().send(Operation.SET_PASSWORD,
                context.scripts().setPassword(username, Optional.empty(), newPassword));
    }

    public List<UserInfo> list() {
        return list(List.of());
    }

    public List<UserInfo> list(Filter filter) {
        return list(List.of(new PipelineStep.FilterStep(filter)));
    }

    private void create(String username, String password, Optional<Permissions> permissions) {
        context.channel().send(Operation.CREATE_USER, context.scripts().createUser(username, password, permissions));
    }

    private List<UserInfo> list(List<PipelineStep> steps) {
        try {
            final var reply = context.channel().send(Operation.LIST_USERS, context.scripts().listUsers(steps));
            return ResponseDecoder.documents(reply, "users").stream().map(Descriptors::user).toList();
        } catch (NotFoundException e) {
            if (e.hasCode(ErrorCode.NO_USERS_FOUND) || e.hasCode(ErrorCode.NO_RESULTS)) {
                return List.of();
            }
            throw e;
        }
    }
}
