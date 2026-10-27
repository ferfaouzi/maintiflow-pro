from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: str = "development"
    database_url: str
    jwt_secret: str
    jwt_algorithm: str = "HS256"
    access_token_hours: int = 12
    storage_dir: str = "./storage"
    max_upload_mb: int = 8
    cors_origins: str = ""
    site_code: str = "DEMO"
    login_max_attempts: int = 5
    login_lock_minutes: int = 15

    @field_validator("jwt_secret")
    @classmethod
    def secret_strength(cls, v: str) -> str:
        if len(v) < 32 or v.startswith("CHANGE_ME"):
            raise ValueError("JWT_SECRET doit être une valeur aléatoire d'au moins 32 caractères")
        return v

    @property
    def is_production(self) -> bool:
        return self.app_env in ("pilot", "production")


@lru_cache
def get_settings() -> Settings:
    return Settings()
