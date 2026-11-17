# LwnrdbError

The base of every error the client raises. `raw_code` holds the server's error code (`NNN-N`) when there is one.

## code

The catalogue entry of `raw_code`, or `None`.

## has_code

Whether the error carries exactly this code.
