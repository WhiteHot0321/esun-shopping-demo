"""整合測試專用 fixture。

這是一套「黑箱契約測試」：直接打執行中的後端，再用 SQL 驗證資料庫的真實狀態。
好處是連 Spring 的 @Transactional、Stored Procedure 與 MySQL 的 CHECK 約束都會被測到，
這些正是純 JUnit + Mockito 單元測試看不到的部分。

前置條件：MySQL 與 Spring Boot 後端都要先啟動，否則整批測試會被 skip 而非 fail。
"""

from __future__ import annotations

from pathlib import Path

import pymysql
import pytest

from esun_ops import db
from esun_ops.client import ShopApiClient
from esun_ops.config import ApiConfig, DbConfig

RESET_SQL = Path(__file__).resolve().parents[3] / "backend" / "DB" / "reset.sql"


@pytest.fixture(scope="session", autouse=True)
def require_environment(api_config: ApiConfig, db_config: DbConfig) -> None:
    if not ShopApiClient(api_config).health():
        pytest.skip(
            "後端 API 無法連線（{}），請先啟動 Spring Boot".format(api_config.base_url),
            allow_module_level=True,
        )
    try:
        with db.connect(db_config):
            pass
    except pymysql.Error as exc:
        pytest.skip("資料庫無法連線: {}".format(exc), allow_module_level=True)


@pytest.fixture
def conn(db_config: DbConfig):
    with db.connect(db_config) as connection:
        yield connection


@pytest.fixture(autouse=True)
def reset_database(db_config: DbConfig):
    """每個測試開始前把資料還原成已知狀態。

    測試之間互相污染是整合測試最常見的失敗來源，寧可多花這一次 reset 的成本，
    也不要讓測試結果取決於執行順序。
    """
    with db.connect(db_config) as connection:
        db.run_sql_file(connection, RESET_SQL)
    yield


@pytest.fixture
def client(api_config: ApiConfig):
    with ShopApiClient(api_config) as c:
        yield c
