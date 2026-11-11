from __future__ import annotations

from lwnrdb._internal.line_reader import TOO_LARGE, LineEvent, LineReader


def test_lines_split_across_chunks_are_joined() -> None:
    reader = LineReader(1024)
    assert reader.push(b'{"a":') == []
    assert reader.push(b'1}\n{"b"') == [LineEvent(b'{"a":1}')]
    assert reader.push(b":2}\n") == [LineEvent(b'{"b":2}')]


def test_a_carriage_return_before_the_newline_is_dropped() -> None:
    assert LineReader(1024).push(b"abc\r\n") == [LineEvent(b"abc")]


def test_an_empty_line_is_reported_empty() -> None:
    assert LineReader(1024).push(b"\n") == [LineEvent(b"")]


def test_an_overlong_line_is_reported_once_and_reading_resumes_at_the_next_line() -> None:
    reader = LineReader(4)
    assert reader.push(b"12345") == []
    assert reader.push(b"678\nok\n") == [TOO_LARGE, LineEvent(b"ok")]


def test_a_line_exactly_at_the_cap_is_kept() -> None:
    assert LineReader(3).push(b"abc\n") == [LineEvent(b"abc")]


def test_one_byte_chunks_assemble_a_line() -> None:
    reader = LineReader(64)
    events = [event for byte in b'{"x":true}\n' for event in reader.push(bytes([byte]))]
    assert events == [LineEvent(b'{"x":true}')]
