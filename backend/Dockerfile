FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY alembic ./alembic
COPY alembic.ini .
COPY scripts ./scripts
RUN useradd --system --uid 10001 maintiflow && mkdir -p /data/storage && chown maintiflow /data/storage
USER maintiflow
ENV STORAGE_DIR=/data/storage
EXPOSE 8000
# Les migrations sont appliquées au démarrage, puis l'API démarre (2 workers suffisent pour une usine).
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 2 --proxy-headers"]
