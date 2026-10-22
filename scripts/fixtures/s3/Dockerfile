# SPDX-License-Identifier: GPL-3.0-only
# Isolated CI fixture only. Upstream MinIO source is AGPL-3.0, not product code.
FROM golang:1.24.8 AS build
RUN git init /src && git -C /src remote add origin https://github.com/minio/minio.git && git -C /src fetch --depth 1 origin 9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a && git -C /src checkout --detach FETCH_HEAD
WORKDIR /src
RUN CGO_ENABLED=0 GOMAXPROCS=2 go build -p 2 -o /minio .
FROM scratch
COPY --from=build /minio /minio
COPY --from=build /src/LICENSE /usr/share/licenses/minio/LICENSE
ENTRYPOINT ["/minio"]
