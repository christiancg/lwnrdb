package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.admin.Admin;
import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.RequestEncoder;
import io.github.christiancg.lwnrdb.client.internal.ScriptRequestEncoder;
import io.github.christiancg.lwnrdb.client.testing.RecordingChannel;
import io.github.christiancg.lwnrdb.client.testing.TestContexts;

final class Recorder {
    final RecordingChannel channel = new RecordingChannel();
    final ClientContext context = TestContexts.recording(channel);
    final Database shop = new Database(context, "shop");
    final Collection<Document> orders = shop.collection("orders");
    final Admin admin = new Admin(context);
    final RequestEncoder encoder = context.encoder();
    final ScriptRequestEncoder scripts = context.scripts();
}
