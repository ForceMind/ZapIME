# HTTPS acceptance host preparation

Actual host compiler and test outputs are preserved here. They do not fill the three Android CA/TLS phases or the ten new Activity lifecycle cases. Host TLS fixture runs use real Go/PostgreSQL and a deliberately trusted test CA in the host-only SSL context; Android product trust is unchanged.

The original driver manifest parser rejection, fixture URI check development failure and canonical example formatting failure are retained in the fixture history where available. Guards, ownership, sanitization and cleanup tests remain separate from actual adb/remount/CA execution.
