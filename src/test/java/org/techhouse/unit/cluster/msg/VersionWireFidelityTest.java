package org.techhouse.unit.cluster.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;

public class VersionWireFidelityTest {
    private static final long BASE_MILLIS = 1_789_000_000_000L;
    private final EJson eJson = IocContainer.get(EJson.class);

    private static List<Long> packedRange() {
        final var versions = new ArrayList<Long>(33);
        for (var logical = 0; logical < 33; logical++) {
            versions.add(HybridClock.pack(BASE_MILLIS, logical));
        }
        return versions;
    }

    private static List<String> asText(List<Long> versions) {
        return versions.stream().map(Object::toString).toList();
    }

    @Test
    public void test_every_logical_counter_round_trips_exactly() {
        for (final var version : packedRange()) {
            final var roundTripped = eJson.fromJson(eJson.toJson(new DigestEntry("a", version, false)),
                    DigestEntry.class);
            assertEquals(version, roundTripped.versionValue(), "logical " + HybridClock.logicalOf(version));
        }
    }

    @Test
    public void test_replication_payload_versions_round_trip_exactly() {
        final var versions = packedRange();
        final var payload = new ReplicationPayload("db", "coll", ReplicationOp.DELETE, null, List.of("a"),
                asText(versions));

        final var roundTripped = eJson.fromJson(eJson.toJson(payload), ReplicationPayload.class);

        assertEquals(asText(versions), roundTripped.getVersions());
    }

    @Test
    public void test_anti_entropy_payload_versions_round_trip_exactly() {
        final var versions = packedRange();
        final var payload = new AntiEntropyPayload("db", "coll");
        payload.setVersions(asText(versions));

        final var roundTripped = eJson.fromJson(eJson.toJson(payload), AntiEntropyPayload.class);

        assertEquals(asText(versions), roundTripped.getVersions());
    }

    @Test
    public void test_a_digest_entry_with_no_version_reads_as_zero() {
        assertEquals(0L, new DigestEntry().versionValue());
    }

    @Test
    public void test_a_version_above_two_to_the_fifty_three_survives() {
        final var version = HybridClock.pack(BASE_MILLIS, 1L);
        assertEquals(1L, HybridClock.logicalOf(version));

        final var roundTripped = eJson.fromJson(eJson.toJson(new DigestEntry("a", version, true)), DigestEntry.class);

        assertEquals(version, roundTripped.versionValue());
        assertEquals(1L, HybridClock.logicalOf(roundTripped.versionValue()));
    }
}
