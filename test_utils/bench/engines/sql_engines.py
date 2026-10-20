from typing import Optional

from engines.base import COLLECTION, FIELDS, INDEXED_FIELDS, Engine

COLUMNS = ("id",) + FIELDS[1:]
COLUMN_LIST = ", ".join(COLUMNS)
PLACEHOLDERS = ", ".join(["%s"] * len(COLUMNS))
ASSIGNMENTS = ", ".join(f"{column} = %s" for column in COLUMNS[1:])

CREATE_TABLE = (f"CREATE TABLE {COLLECTION} (id VARCHAR(64) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                "category VARCHAR(32) NOT NULL, price DOUBLE PRECISION NOT NULL, qty INTEGER NOT NULL, "
                "active BOOLEAN NOT NULL, created VARCHAR(32) NOT NULL, payload TEXT NOT NULL)")
INSERT = f"INSERT INTO {COLLECTION} ({COLUMN_LIST}) VALUES ({PLACEHOLDERS})"
SELECT = f"SELECT {COLUMN_LIST} FROM {COLLECTION}"
GET = f"{SELECT} WHERE id = %s"
UPDATE = f"UPDATE {COLLECTION} SET {ASSIGNMENTS} WHERE id = %s"
DELETE = f"DELETE FROM {COLLECTION} WHERE id = %s"
FIND_EQ = f"{SELECT} WHERE category = %s LIMIT %s"
FIND_RANGE = f"{SELECT} WHERE price >= %s AND price < %s ORDER BY price LIMIT %s"
COUNT_RANGE = f"SELECT COUNT(*) FROM {COLLECTION} WHERE price >= %s"
SCAN_COUNT = f"SELECT COUNT(*) FROM {COLLECTION} WHERE qty = %s"
COUNT_ALL = f"SELECT COUNT(*) FROM {COLLECTION}"


def to_row(doc: dict) -> tuple:
    return tuple(doc[field] for field in FIELDS)


def to_doc(row) -> Optional[dict]:
    if row is None:
        return None
    doc = dict(zip(FIELDS, row))
    doc["active"] = bool(doc["active"])
    return doc


class SqlEngine(Engine):

    def __init__(self, host: str):
        super().__init__(host)
        self.conn = None
        self.cursor = None

    def _open(self):
        raise NotImplementedError

    def _bulk(self, rows: list) -> None:
        raise NotImplementedError

    def connect(self) -> None:
        self.close()
        self.conn = self._open()
        self.cursor = self.conn.cursor()

    def close(self) -> None:
        if self.conn is not None:
            self.conn.close()
        self.conn = None
        self.cursor = None

    def _scalar(self, sql: str, params: tuple = ()) -> int:
        self.cursor.execute(sql, params)
        return int(self.cursor.fetchone()[0])

    def setup(self) -> None:
        self.cursor.execute(f"DROP TABLE IF EXISTS {COLLECTION}")
        self.cursor.execute(CREATE_TABLE)
        for field in INDEXED_FIELDS:
            self.cursor.execute(f"CREATE INDEX idx_{field} ON {COLLECTION} ({field})")

    def bulk_insert(self, docs: list) -> None:
        self._bulk([to_row(doc) for doc in docs])

    def insert(self, doc: dict) -> None:
        self.cursor.execute(INSERT, to_row(doc))

    def get(self, key: str) -> Optional[dict]:
        self.cursor.execute(GET, (key,))
        return to_doc(self.cursor.fetchone())

    def update(self, doc: dict) -> bool:
        row = to_row(doc)
        self.cursor.execute(UPDATE, row[1:] + row[:1])
        return self.cursor.rowcount == 1

    def delete(self, key: str) -> bool:
        self.cursor.execute(DELETE, (key,))
        return self.cursor.rowcount == 1

    def find_eq(self, category: str, limit: int) -> list:
        self.cursor.execute(FIND_EQ, (category, limit))
        return [to_doc(row) for row in self.cursor.fetchall()]

    def find_range(self, low: float, high: float, limit: int) -> list:
        self.cursor.execute(FIND_RANGE, (low, high, limit))
        return [to_doc(row) for row in self.cursor.fetchall()]

    def count_range(self, low: float) -> int:
        return self._scalar(COUNT_RANGE, (low,))

    def scan_count(self, qty: int) -> int:
        return self._scalar(SCAN_COUNT, (qty,))

    def count_all(self) -> int:
        return self._scalar(COUNT_ALL)


class MysqlEngine(SqlEngine):
    name = "mysql"

    def __init__(self, host: str = "mysql"):
        super().__init__(host)

    def _open(self):
        import mysql.connector
        return mysql.connector.connect(host=self.host, user="root", password="bench", database="bench",
                                       autocommit=True, use_pure=False)

    def _bulk(self, rows: list) -> None:
        self.conn.start_transaction()
        self.cursor.executemany(INSERT, rows)
        self.conn.commit()


class PostgresEngine(SqlEngine):
    name = "postgres"

    def __init__(self, host: str = "postgres"):
        super().__init__(host)

    def _open(self):
        import psycopg
        return psycopg.connect(host=self.host, user="postgres", password="bench", dbname="bench", autocommit=True)

    def _bulk(self, rows: list) -> None:
        with self.conn.transaction():
            self.cursor.executemany(INSERT, rows)
