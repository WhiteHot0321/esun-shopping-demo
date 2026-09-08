"""ESUN Shopping Demo 的 REST API client。

實作重點：後端的 GlobalExceptionHandler 無論成功或失敗都回 HTTP 200，
成敗訊息包在 ApiResponse 的 success / message 欄位裡。
因此這一層必須同時檢查 HTTP 狀態碼「與」body 的 success 旗標，
否則所有錯誤情境都會被誤判成成功。
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any, Iterable, Sequence

import requests

from .config import ApiConfig


class ApiError(RuntimeError):
    """後端以 ApiResponse.success = false 回覆時拋出。"""

    def __init__(self, message: str, *, status_code: int, payload: Any = None) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.payload = payload


class ShopApiClient:
    def __init__(self, config: ApiConfig | None = None) -> None:
        self.config = config or ApiConfig.from_env()
        # 壓測時每個 thread 各自持有 client，Session 可重用 TCP 連線，
        # 避免把「建立連線的成本」誤算進 API 延遲。
        self.session = requests.Session()

    # ---------- 內部工具 ----------

    def _url(self, path: str) -> str:
        return f"{self.config.base_url}{path}"

    def _unwrap(self, response: requests.Response) -> Any:
        response.raise_for_status()
        try:
            body = response.json()
        except ValueError as exc:
            raise ApiError(
                f"回應不是合法 JSON: {response.text[:200]}",
                status_code=response.status_code,
            ) from exc

        if not body.get("success", False):
            raise ApiError(
                body.get("message") or "未知錯誤",
                status_code=response.status_code,
                payload=body,
            )
        return body.get("data")

    # ---------- API ----------

    def health(self) -> bool:
        """以「查詢可購買商品」當作健康檢查，後端沒有 actuator。"""
        try:
            self.list_available_products()
            return True
        except (requests.RequestException, ApiError):
            return False

    def create_product(
        self,
        product_id: str,
        product_name: str,
        price: Decimal | float | str,
        quantity: int,
    ) -> None:
        payload = {
            "productId": product_id,
            "productName": product_name,
            # Decimal 不能直接 JSON 序列化，轉字串可保留精度（Java 端是 BigDecimal）
            "price": str(price),
            "quantity": quantity,
        }
        self._unwrap(
            self.session.post(
                self._url("/api/products"), json=payload, timeout=self.config.timeout
            )
        )

    def list_available_products(self) -> list[dict[str, Any]]:
        data = self._unwrap(
            self.session.get(
                self._url("/api/products/available"), timeout=self.config.timeout
            )
        )
        return data or []

    def create_order(
        self,
        member_id: str,
        items: Sequence[tuple[str, int]] | Iterable[dict[str, Any]],
        pay_status: str = "1",
    ) -> str:
        """建立訂單，回傳後端產生的 orderId。

        items 接受 [("P002", 2), ...] 或 [{"productId": "P002", "quantity": 2}, ...]。
        """
        normalised: list[dict[str, Any]] = []
        for item in items:
            if isinstance(item, dict):
                normalised.append(item)
            else:
                product_id, quantity = item
                normalised.append({"productId": product_id, "quantity": quantity})

        payload = {
            "memberId": member_id,
            "payStatus": pay_status,
            "items": normalised,
        }
        data = self._unwrap(
            self.session.post(
                self._url("/api/orders"), json=payload, timeout=self.config.timeout
            )
        )
        return data["orderId"]

    def close(self) -> None:
        self.session.close()

    def __enter__(self) -> "ShopApiClient":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()
