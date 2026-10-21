FROM golang:1.27.3 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /zapime-sync ./cmd/zapime-sync
RUN go run ./cmd/collect-notices /licenses

FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /zapime-sync /usr/local/bin/zapime-sync
COPY LICENSE /usr/share/licenses/ZapIME/LICENSE
COPY --from=build /licenses /usr/share/licenses/dependencies
USER 65532:65532
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=4s --start-period=10s CMD ["/usr/local/bin/zapime-sync", "health"]
ENTRYPOINT ["/usr/local/bin/zapime-sync"]
