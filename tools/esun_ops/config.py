"""集中管理連線設定。

預設值刻意對齊 backend/src/main/resources/application.yml 與 docker-compose.yml，
讓工具在未設定任何環境變數時就能直接跑起來；正式環境則以環境變數覆寫。
"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class ApiConfig:
    base_url: str
    timeout: float

    @staticmethod
    def from_env() -> "ApiConfig":
        return ApiConfig(
            base_url=os.getenv("ESUN_API_BASE", "http://localhost:8080").rstrip("/"),
            timeout=float(os.getenv("ESUN_API_TIMEOUT", "10")),
        )


@dataclass(frozen=True)
class DbConfig:
    host: str
    port: int
    user: str
    password: str
    database: str

    @staticmethod
    def from_env() -> "DbConfig":
        return DbConfig(
            host=os.getenv("ESUN_DB_HOST", "127.0.0.1"),
            port=int(os.getenv("ESUN_DB_PORT", "3307")),
            user=os.getenv("ESUN_DB_USER", "root"),
            password=os.getenv("ESUN_DB_PASSWORD", "pierce"),
            database=os.getenv("ESUN_DB_NAME", "esun_shop"),
        )
