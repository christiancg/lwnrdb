from __future__ import annotations

from lwnrdb._internal.frame_router import Route, is_listen_end, route


def test_a_listen_update_is_a_push() -> None:
    assert route({"type": "LISTEN", "isUpdate": True}, awaiting_close=False) is Route.PUSH


def test_a_listen_end_is_a_push() -> None:
    frame = {"type": "LISTEN", "errorCode": "410-1"}
    assert is_listen_end(frame)
    assert route(frame, awaiting_close=False) is Route.PUSH


def test_the_initial_listen_reply_and_a_listen_refusal_are_responses() -> None:
    assert route({"type": "LISTEN", "isUpdate": False, "listenId": "l"}, awaiting_close=False) is Route.RESPONSE
    assert route({"type": "LISTEN", "status": "ERROR", "errorCode": "400-25"}, awaiting_close=False) is Route.RESPONSE


def test_an_unsolicited_close_is_a_server_close_and_an_awaited_one_a_response() -> None:
    frame = {"type": "CLOSE_CONNECTION", "errorCode": "413-1"}
    assert route(frame, awaiting_close=False) is Route.SERVER_CLOSE
    assert route(frame, awaiting_close=True) is Route.RESPONSE


def test_any_other_frame_is_a_response() -> None:
    assert route({"type": "SAVE", "isUpdate": True}, awaiting_close=False) is Route.RESPONSE
