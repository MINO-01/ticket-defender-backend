FROM golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191 AS builder

ENV CGO_ENABLED=0 GOBIN=/out
RUN go install github.com/minio/minio@v0.0.0-20260212201848-7aac2a2c5b7c \
    && go install github.com/minio/mc@RELEASE.2025-08-13T08-35-41Z

FROM alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=builder /out/minio /usr/local/bin/minio
COPY --from=builder /out/mc /usr/local/bin/mc
EXPOSE 9000
ENTRYPOINT ["minio"]
