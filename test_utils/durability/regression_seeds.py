REGRESSION_SEEDS = [
    (2, "random", "baseline, strict rebuild"),
    (3, "random", "baseline, torn rebuild"),
    (4, "pointed", "baseline, strict rebuild"),
    (5, "pointed", "baseline, torn rebuild"),
    (302, "pointed", "HALT at WAL_REWRITE_BEGUN, strict rebuild"),
    (321, "pointed", "HALT at PAGE_VACUUM_RENAMED, torn rebuild"),
    (322, "pointed", "HALT at PK_INDEX_REPLACED, strict rebuild"),
    (6, "fsync-failure", "a failed force of the collection's write-ahead log, strict rebuild"),
    (200, "fsync-failure", "a failed force of the collection's write-ahead log, strict rebuild"),
]
