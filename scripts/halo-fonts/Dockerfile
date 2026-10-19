FROM registry.fit2cloud.com/halo/halo:2.27.0@sha256:4cef0ad93ed967b34970c97ae325045100c26d69157c095007c2e3bab95576d1

COPY requirements.txt /opt/khon-font-book/requirements.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv \
    && python3 -m venv /opt/khon-font-book/python \
    && /opt/khon-font-book/python/bin/pip install --no-cache-dir \
        -r /opt/khon-font-book/requirements.txt \
    && rm -rf /var/lib/apt/lists/*

ENV KHON_FONT_BOOK_PYTHON=/opt/khon-font-book/python/bin/python
