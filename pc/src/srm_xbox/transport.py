"""BLE FFE1 and serial transports for the SRM wire stream."""

from __future__ import annotations

import asyncio
from collections.abc import Callable

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
import serial
from serial.tools import list_ports

FFE1_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
ReceiveCallback = Callable[[bytes], None]


async def discover_ble(timeout: float = 5.0) -> list[BLEDevice]:
    devices = await BleakScanner.discover(timeout=timeout)
    return sorted(devices, key=lambda item: ((item.name or "").lower(), item.address))


async def resolve_ble_device(selector: str, timeout: float = 8.0) -> BLEDevice:
    selector_lower = selector.lower()
    devices = await discover_ble(timeout)
    exact = [
        device
        for device in devices
        if device.address.lower() == selector_lower or (device.name or "").lower() == selector_lower
    ]
    if len(exact) == 1:
        return exact[0]
    partial = [
        device
        for device in devices
        if selector_lower in device.address.lower() or selector_lower in (device.name or "").lower()
    ]
    if len(partial) == 1:
        return partial[0]
    if not partial:
        raise RuntimeError(f"BLE device not found: {selector!r}")
    matches = ", ".join(f"{device.name or '(unnamed)'} [{device.address}]" for device in partial)
    raise RuntimeError(f"BLE selector is ambiguous: {matches}")


class BleFfe1Transport:
    def __init__(self, device: BLEDevice, receive: ReceiveCallback) -> None:
        self._device = device
        self._receive = receive
        self._client: BleakClient | None = None
        self._response = False
        self._notify = False

    @property
    def description(self) -> str:
        return f"BLE {self._device.name or '(unnamed)'} [{self._device.address}]"

    async def connect(self) -> None:
        client = BleakClient(self._device)
        await client.connect()
        try:
            characteristic = client.services.get_characteristic(FFE1_UUID)
            if characteristic is None:
                raise RuntimeError("connected device has no FFE1 characteristic")
            properties = {item.lower() for item in characteristic.properties}
            if "write-without-response" in properties:
                self._response = False
            elif "write" in properties:
                self._response = True
            else:
                raise RuntimeError("FFE1 characteristic is not writable")
            if "notify" in properties or "indicate" in properties:
                await client.start_notify(characteristic, self._on_notification)
                self._notify = True
            self._client = client
        except BaseException:
            await client.disconnect()
            raise

    def _on_notification(self, _sender: object, data: bytearray) -> None:
        self._receive(bytes(data))

    async def send(self, data: bytes) -> None:
        if self._client is None or not self._client.is_connected:
            raise ConnectionError("BLE link is disconnected")
        await self._client.write_gatt_char(FFE1_UUID, data, response=self._response)

    async def close(self) -> None:
        client, self._client = self._client, None
        if client is None:
            return
        if self._notify and client.is_connected:
            try:
                await client.stop_notify(FFE1_UUID)
            except Exception:
                pass
        await client.disconnect()


class SerialTransport:
    def __init__(self, port: str, baudrate: int, receive: ReceiveCallback) -> None:
        self._port = port
        self._baudrate = baudrate
        self._receive = receive
        self._serial: serial.Serial | None = None
        self._reader: asyncio.Task[None] | None = None

    @property
    def description(self) -> str:
        return f"serial {self._port} @ {self._baudrate} 8N1"

    async def connect(self) -> None:
        self._serial = serial.Serial(
            self._port,
            self._baudrate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=0.1,
            write_timeout=0.5,
        )
        self._reader = asyncio.create_task(self._read_loop())

    async def _read_loop(self) -> None:
        assert self._serial is not None
        while self._serial.is_open:
            data = await asyncio.to_thread(self._serial.read, 256)
            if data:
                self._receive(data)

    async def send(self, data: bytes) -> None:
        if self._serial is None or not self._serial.is_open:
            raise ConnectionError("serial port is closed")
        await asyncio.to_thread(self._serial.write, data)

    async def close(self) -> None:
        reader, self._reader = self._reader, None
        port, self._serial = self._serial, None
        if port is not None and port.is_open:
            port.close()
        if reader is not None:
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)


def serial_ports() -> list[tuple[str, str]]:
    return [(item.device, item.description) for item in list_ports.comports()]
