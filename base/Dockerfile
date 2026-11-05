# syntax=docker.io/docker/dockerfile:1

# Base image for Jib (see build.gradle), which can't add operating system packages itself:
# fonts for PDFBox, which renders the first-page previews and draws the sample data's PDFs.
# CI publishes it as bloomper/fgv-archive-server-base:25 before each image build, so the JRE gets security updates.
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core fontconfig \
    && rm -rf /var/lib/apt/lists/*
