# Example runtime only. Inspect and pin the base image by digest before production deployment.
# This image is only a runtime container; the gateway never builds or runs task code on the host.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
USER 65534:65534
ENTRYPOINT []
