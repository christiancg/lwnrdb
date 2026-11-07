import unittest

import workload
from workload import Settings


def settings(docs: int = 1_000, batch: int = 100, bulk_warmup: float = 0.5) -> Settings:
    return Settings(docs=docs, batch=batch, payload=16, concurrency=2, ops_scale=1.0, warmup=0.1, seed=1,
                    bulk_warmup=bulk_warmup)


def bulk_phase(chosen: Settings):
    return next(phase for phase in workload.phases(chosen) if phase.name == "bulk_insert")


def loaded_ids(phase) -> list:
    return [doc["_id"] for n in range(phase.ops + phase.warmup) for doc in phase.argument(None, n)]


class BulkPhaseTest(unittest.TestCase):
    def test_bulk_warmup_loads_every_document_once(self):
        phase = bulk_phase(settings(docs=1_050))

        ids = loaded_ids(phase)

        self.assertEqual(1_050, len(ids))
        self.assertEqual(sorted(f"d{i}" for i in range(1_050)), sorted(ids))
        self.assertEqual(5, phase.warmup)
        self.assertEqual(6, phase.ops)

    def test_bulk_documents_count_only_the_timed_batches(self):
        self.assertEqual(600, bulk_phase(settings(docs=1_050)).documents)
        self.assertEqual(500, bulk_phase(settings(docs=1_000)).documents)

    def test_zero_bulk_warmup_keeps_the_old_phase(self):
        phase = bulk_phase(settings(bulk_warmup=0.0))

        self.assertEqual((10, 0, 1_000), (phase.ops, phase.warmup, phase.documents))

    def test_a_single_batch_is_always_timed(self):
        phase = bulk_phase(settings(docs=50, bulk_warmup=0.9))

        self.assertEqual((1, 0, 50), (phase.ops, phase.warmup, phase.documents))


if __name__ == "__main__":
    unittest.main()
