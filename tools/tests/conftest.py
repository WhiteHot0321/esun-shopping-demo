"""全體測試共用的 fixture。

這裡只提供設定物件；需要真實環境（後端 + DB）的 fixture 放在
tests/integration/conftest.py，好讓 tests/unit 下的純邏輯測試
在沒有任何服務啟動時也能執行。
"""

from __future__ import annotations

import pytest

from esun_ops.config import ApiConfig, DbConfig


@pytest.fixture(scope="session")
def api_config() -> ApiConfig:
    return ApiConfig.from_env()


@pytest.fixture(scope="session")
def db_config() -> DbConfig:
    return DbConfig.from_env()
