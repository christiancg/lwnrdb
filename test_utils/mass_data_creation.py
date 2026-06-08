import socket
import random
import json
import threading
import string
from datetime import datetime, timedelta

HOST = "127.0.0.1"  # The server's hostname or IP address
PORT = 8989  # The port used by the server

NUM_THREADS = 10
NUM_ENTRIES_PER_THREAD = 100

BULK_CREATION = False

base_message_individual = {"type": "SAVE", "databaseName": "test", "collectionName": "testCollection"}
base_message_bulk = {"type": "BULK_SAVE", "databaseName": "test", "collectionName": "testCollection"}

def randomInt(size = 4):
    return random.randint(1, 10**size)

def randomBool():
    return random.randint(1, 2) % 2 == 0

def randomString(size=6, chars=string.ascii_uppercase + string.digits):
    return ''.join(random.choice(chars) for _ in range(size))

def randomDatetime(variance=8):
    delta = (1 if random.randint(1, 2) % 2 == 0 else -1) * random.randint(1, 10**variance)
    random_time = datetime.now() + timedelta(seconds = delta)
    return '#datetime(' + random_time.strftime('%Y-%m-%dT%H:%M:%S') + ')'

def one_by_one_creation(s, f):
    for i in range(0, NUM_ENTRIES_PER_THREAD):
        obj = {"aNumber": randomInt(size=2), "aString": randomString(size=2), "aBoolean": randomBool(), "aDatetime": randomDatetime()}
        message = json.dumps({**base_message_individual, "object": obj})
        s.sendall(message.encode() + b'\n')
        f.readline()

def bulk_creation(s, f):
    arr = []
    for i in range(0, NUM_ENTRIES_PER_THREAD):
        rstr = randomString(size=3)
        obj = {"aNumber": randomInt(size=3), "aString": rstr, "aBoolean": randomBool(), "aDatetime": randomDatetime()}
        arr.append(obj)
    message = json.dumps({**base_message_bulk, "objects": arr})
    s.sendall(message.encode() + b'\n')
    f.readline()

def thread_function(thread_number):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((HOST, PORT))
        f = s.makefile('rb')
        print(f"Start sending from {thread_number}")
        if BULK_CREATION:
            bulk_creation(s, f)
        else:
            one_by_one_creation(s, f)
        print(f"Finished sending from {thread_number}")

start = datetime.now()
start_time = start.strftime("%H:%M:%S")
print(f"Started at {start_time!r}")
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