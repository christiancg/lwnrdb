import socket
import random
import json
import threading
import string
from datetime import datetime, timedelta

HOST = "127.0.0.1"
PORT = 8989
USERNAME = "admin"
PASSWORD = "Tst-Admin-9xQ2mV"

NUM_THREADS = 10
NUM_ENTRIES_PER_THREAD = 1000

BULK_CREATION = False

base_message_individual = {"type": "SAVE", "databaseName": "test", "collectionName": "testCollection"}
base_message_bulk = {"type": "BULK_SAVE", "databaseName": "test", "collectionName": "testCollection"}

def random_int(size = 4):
    return random.randint(1, 10**size)

def random_bool():
    return random.randint(1, 2) % 2 == 0

def random_string(size=6, chars=string.ascii_uppercase + string.digits):
    return ''.join(random.choice(chars) for _ in range(size))

def random_datetime(variance=8):
    delta = (1 if random.randint(1, 2) % 2 == 0 else -1) * random.randint(1, 10**variance)
    random_time = datetime.now() + timedelta(seconds = delta)
    return '#datetime(' + random_time.strftime('%Y-%m-%dT%H:%M:%S') + ')'

def one_by_one_creation(s, f):
    for _ in range(NUM_ENTRIES_PER_THREAD):
        obj = {"aNumber": random_int(size=2), "aString": random_string(size=2), "aBoolean": random_bool(), "aDatetime": random_datetime()}
        message = json.dumps({**base_message_individual, "object": obj})
        s.sendall(message.encode() + b'\n')
        f.readline()

def bulk_creation(s, f):
    arr = []
    for _ in range(NUM_ENTRIES_PER_THREAD):
        random_str = random_string(size=3)
        obj = {"aNumber": random_int(size=3), "aString": random_str, "aBoolean": random_bool(), "aDatetime": random_datetime()}
        arr.append(obj)
    message = json.dumps({**base_message_bulk, "objects": arr})
    s.sendall(message.encode() + b'\n')
    f.readline()

def authenticate(s, f):
    message = json.dumps({"type": "AUTHENTICATE", "username": USERNAME, "password": PASSWORD})
    s.sendall(message.encode() + b'\n')
    f.readline()

def thread_function(thread_number):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((HOST, PORT))
        f = s.makefile('rb')
        authenticate(s, f)
        print(f"Start sending from {thread_number}")
        if BULK_CREATION:
            bulk_creation(s, f)
        else:
            one_by_one_creation(s, f)
        print(f"Finished sending from {thread_number}")

def setup_database():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((HOST, PORT))
        f = s.makefile('rb')
        authenticate(s, f)

        list_db_msg = json.dumps({"type": "LIST_DATABASES"})
        s.sendall(list_db_msg.encode() + b'\n')
        response = json.loads(f.readline().decode())
        databases = response.get("databases", [])

        if "test" not in databases:
            print("Creating database 'test'...")
            create_db_msg = json.dumps({"type": "CREATE_DATABASE", "databaseName": "test"})
            s.sendall(create_db_msg.encode() + b'\n')
            response = f.readline().decode()
            print(f"CREATE_DATABASE response: {response.strip()}")
        else:
            print("Database 'test' already exists")

        list_coll_msg = json.dumps({"type": "LIST_COLLECTIONS", "databaseName": "test"})
        s.sendall(list_coll_msg.encode() + b'\n')
        response = json.loads(f.readline().decode())
        collections = response.get("collections") or []

        if "testCollection" not in collections:
            print("Creating collection 'testCollection'...")
            create_coll_msg = json.dumps({"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "testCollection"})
            s.sendall(create_coll_msg.encode() + b'\n')
            response = f.readline().decode()
            print(f"CREATE_COLLECTION response: {response.strip()}")
        else:
            print("Collection 'testCollection' already exists")

start = datetime.now()
start_time = start.strftime("%H:%M:%S")
print(f"Started at {start_time!r}")
print("Setting up database and collection...")
setup_database()
print("Database setup complete, starting data creation...")
threads = []
for i in range(0,NUM_THREADS):
    x = threading.Thread(target=thread_function, args=(i,))
    x.start()
    threads.append(x)

for t in threads:
    t.join()

end = datetime.now()
end_time = end.strftime("%H:%M:%S")
print(f"Finished at {end_time!r}")
time_spent = (end - start).total_seconds()
total_entry_count = NUM_THREADS * NUM_ENTRIES_PER_THREAD
print(f"Created {total_entry_count!r} entries in {time_spent!r} seconds")