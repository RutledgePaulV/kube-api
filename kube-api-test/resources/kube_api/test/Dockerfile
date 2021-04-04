FROM golang AS BUILDER
RUN GOOS=linux GOARCH=amd64 GO111MODULE="on" CGO_ENABLED=0 go get sigs.k8s.io/kind@v0.10.0
FROM docker:19.03.13
COPY --from=BUILDER /go/bin/kind /usr/local/bin/kind
CMD ["/usr/local/bin/kind"]

# builder -t test .
# docker run --privileged -v /var/run/docker.sock:/var/run/docker.sock test create cluster