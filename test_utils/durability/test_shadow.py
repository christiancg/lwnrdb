import os
import random
import tempfile
import unittest

from durability import shadow


class EagerRebuildTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lwnrdb-shadow-test-")
        self.data = os.path.join(self.root, "db")
        self.shadow_root = os.path.join(self.root, "shadow")
        os.makedirs(os.path.join(self.data, "coll"))
        os.makedirs(os.path.join(self.shadow_root, shadow.BLOBS))
        self.next_id = 0

    def live(self, relative: str, content: bytes) -> str:
        path = os.path.join(self.data, relative)
        with open(path, "wb") as fp:
            fp.write(content)
        return path

    def forced(self, path: str, content: bytes) -> str:
        self.next_id += 1
        key_id = str(self.next_id)
        with open(os.path.join(self.shadow_root, shadow.BLOBS, key_id), "wb") as fp:
            fp.write(content)
        with open(os.path.join(self.shadow_root, shadow.BLOBS, shadow.KEY_MAP), "a") as fp:
            fp.write(f"{key_id}\t(dev=1,ino={os.stat(path).st_ino})\n")
        return key_id

    def rebuilt(self, relative: str) -> bytes:
        with open(os.path.join(self.data, relative), "rb") as fp:
            return fp.read()

    def eager(self):
        return shadow.rebuild(self.shadow_root, self.data, shadow.EAGER, random.Random(1))

    def test_eager_keeps_a_renamed_file_with_its_last_forced_blob(self):
        temp = self.live("coll/c-f-String.idx.repair", b"forced base\n")
        self.forced(temp, b"forced base\n")
        os.rename(temp, os.path.join(self.data, "coll/c-f-String.idx"))

        report = self.eager()

        self.assertEqual(b"forced base\n", self.rebuilt("coll/c-f-String.idx"))
        self.assertFalse(os.path.exists(os.path.join(self.data, "coll/c-f-String.idx.repair")))
        self.assertEqual([], report.files_dropped)

    def test_eager_empties_a_live_file_never_forced(self):
        self.live("coll/c-f-String.idxlog", b"+\x1fk\x1fd1\n")

        report = self.eager()

        self.assertEqual(b"", self.rebuilt("coll/c-f-String.idxlog"))
        self.assertEqual(["coll/c-f-String.idxlog"], report.files_emptied)

    def test_eager_keeps_only_the_forced_prefix_of_a_live_file(self):
        page = self.live("coll/c-0.dat", b"forced\n")
        self.forced(page, b"forced\n")
        with open(page, "ab") as fp:
            fp.write(b"unforced\n")

        report = self.eager()

        self.assertEqual(b"forced\n", self.rebuilt("coll/c-0.dat"))
        self.assertEqual(["coll/c-0.dat"], report.files_truncated)

    def test_eager_keeps_a_deleted_name_deleted(self):
        gone = self.live("coll/c-f-String.idxlog", b"+\x1fk\x1fd1\n")
        self.forced(gone, b"+\x1fk\x1fd1\n")
        os.remove(gone)

        self.eager()

        self.assertFalse(os.path.exists(os.path.join(self.data, "coll/c-f-String.idxlog")))

    def test_a_torn_key_map_line_is_skipped(self):
        page = self.live("coll/c-0.dat", b"forced\n")
        self.forced(page, b"forced\n")
        with open(os.path.join(self.shadow_root, shadow.BLOBS, shadow.KEY_MAP), "a") as fp:
            fp.write("9\t(dev=1,in")

        self.eager()

        self.assertEqual(b"forced\n", self.rebuilt("coll/c-0.dat"))

    def test_strict_still_rolls_names_back_to_the_last_forced_listing(self):
        self.live("coll/c-0.dat", b"unlisted\n")

        report = shadow.rebuild(self.shadow_root, self.data, shadow.STRICT, random.Random(1))

        self.assertFalse(os.path.exists(os.path.join(self.data, "coll")))
        self.assertEqual(["coll/c-0.dat"], report.files_dropped)


if __name__ == "__main__":
    unittest.main()
