import logging
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import text

from . import __version__
from .config import get_settings
from .db import engine
from .routers import admin, auth, interventions, referentials

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("maintiflow")

settings = get_settings()
app = FastAPI(
    title="MaintiFlow Pro API",
    version=__version__,
    description="API des bons d'intervention numériques Production ↔ Maintenance. "
                "Authentification : `POST /api/v1/auth/login` puis en-tête `Authorization: Bearer <jeton>`.",
    docs_url="/docs" if not settings.is_production else None,
    redoc_url="/redoc" if not settings.is_production else None,
    openapi_url="/openapi.json" if not settings.is_production else None,
)

if settings.cors_origins:
    app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins.split(","), allow_methods=["*"],
                       allow_headers=["*"])


@app.middleware("http")
async def request_log(request: Request, call_next):
    rid = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
    start = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception:
        log.exception("rid=%s %s %s erreur non gérée", rid, request.method, request.url.path)
        return JSONResponse({"detail": "Erreur interne du serveur", "request_id": rid}, status_code=500)
    response.headers["X-Request-ID"] = rid
    response.headers["X-Content-Type-Options"] = "nosniff"
    log.info("rid=%s %s %s %s %.0fms", rid, request.method, request.url.path, response.status_code,
             (time.perf_counter() - start) * 1000)
    return response


API = "/api/v1"
app.include_router(auth.router, prefix=API)
app.include_router(referentials.router, prefix=API)
app.include_router(referentials.admin, prefix=API)
app.include_router(admin.router, prefix=API)
app.include_router(interventions.router, prefix=API)
app.include_router(interventions.files_router, prefix=API)


@app.get("/health", tags=["Supervision"])
def health():
    with engine.connect() as c:
        c.execute(text("SELECT 1"))
    return {"status": "ok", "version": __version__}
