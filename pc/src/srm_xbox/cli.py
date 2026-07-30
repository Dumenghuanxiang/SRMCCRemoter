"""Command-line entry point for the SRM Xbox bridge."""

from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass
import sys

from .protocol import (
    PRO_BUTTON_A,
    PRO_BUTTON_B,
    PRO_BUTTON_DPAD_DOWN,
    PRO_BUTTON_DPAD_LEFT,
    PRO_BUTTON_DPAD_RIGHT,
    PRO_BUTTON_DPAD_UP,
    PRO_BUTTON_L1,
    PRO_BUTTON_L2,
    PRO_BUTTON_R1,
    PRO_BUTTON_R2,
    PRO_BUTTON_SELECT,
    PRO_BUTTON_START,
    PRO_BUTTON_THUMB_L,
    PRO_BUTTON_THUMB_R,
    PRO_BUTTON_X,
    PRO_BUTTON_Y,
    ProControlState,
    Frame,
    StreamDecoder,
    TYPE_ACK,
    TYPE_ERROR,
    TYPE_HELLO,
    TYPE_LOG,
    TYPE_PRO_CONTROL,
    TYPE_STATUS,
    encode_pro_control,
    encode_hello,
)
from .transport import (
    BleFfe1Transport,
    SerialTransport,
    discover_ble,
    resolve_ble_device,
    serial_ports,
)
from .xinput import ControllerDisconnected, XInputController, map_to_srm


TYPE_NAMES = {
    TYPE_HELLO: "HELLO",
    TYPE_ACK: "ACK",
    TYPE_ERROR: "ERROR",
    TYPE_LOG: "LOG",
    TYPE_PRO_CONTROL: "PRO_CONTROL",
    TYPE_STATUS: "STATUS",
}


@dataclass(slots=True)
class Sequence:
    value: int = 0

    def take(self) -> int:
        current = self.value
        self.value = (current + 1) & 0xFF
        return current


class IncomingReporter:
    def __init__(self) -> None:
        self._decoder = StreamDecoder()

    def feed(self, data: bytes) -> None:
        for frame in self._decoder.feed(data):
            self._print(frame)

    @staticmethod
    def _print(frame: Frame) -> None:
        name = TYPE_NAMES.get(frame.type, f"TYPE_{frame.type}")
        if frame.type == TYPE_LOG and frame.payload:
            detail = frame.payload[1:].decode("utf-8", errors="replace").rstrip()
            detail = f"level={frame.payload[0]} {detail}"
        elif frame.type == TYPE_ERROR and len(frame.payload) >= 2:
            text = frame.payload[2:].decode("utf-8", errors="replace").rstrip()
            detail = f"request={frame.payload[0]} code={frame.payload[1]} {text}".rstrip()
        else:
            detail = frame.payload.hex(" ").upper()
        print(f"\nRX {name} seq={frame.sequence}: {detail}")


def state_line(state: ProControlState, rate: int) -> str:
    button_text = "".join(
        name if state.buttons & bit else "-"
        for bit, name in ((PRO_BUTTON_A, "A"), (PRO_BUTTON_B, "B"),
                          (PRO_BUTTON_X, "X"), (PRO_BUTTON_Y, "Y"))
    )
    aux_text = "".join(
        name if state.buttons & bit else "-"
        for bit, name in ((PRO_BUTTON_L1, "1"), (PRO_BUTTON_R1, "2"),
                          (PRO_BUTTON_L2, "3"), (PRO_BUTTON_R2, "4"),
                          (PRO_BUTTON_THUMB_L, "L"), (PRO_BUTTON_THUMB_R, "R"),
                          (PRO_BUTTON_START, "S"), (PRO_BUTTON_SELECT, "B"))
    )
    dpad_text = "".join(
        name for bit, name in ((PRO_BUTTON_DPAD_UP, "U"), (PRO_BUTTON_DPAD_DOWN, "D"),
                               (PRO_BUTTON_DPAD_LEFT, "L"), (PRO_BUTTON_DPAD_RIGHT, "R"))
        if state.buttons & bit
    ) or "-"
    return (
        f"{rate:3d} Hz  LX={state.left_x:6d} LY={state.left_y:6d} "
        f"RX={state.right_x:6d} RY={state.right_y:6d}  "
        f"ABXY={button_text} AUX={aux_text} LT={state.left_trigger:3d} "
        f"RT={state.right_trigger:3d} D={dpad_text}"
    )


async def send_neutral(transport: BleFfe1Transport | SerialTransport, sequence: Sequence) -> None:
    for _ in range(3):
        try:
            await transport.send(encode_pro_control(ProControlState(), sequence.take()))
            await asyncio.sleep(0.02)
        except Exception:
            return


async def stream_controller(
    transport: BleFfe1Transport | SerialTransport,
    controller: XInputController,
    sequence: Sequence,
    *,
    rate: int,
    deadzone: int,
    trigger_threshold: int,
    ble: bool,
) -> None:
    await transport.send(encode_hello(sequence.take(), ble=ble))
    period = 1.0 / rate
    loop = asyncio.get_running_loop()
    deadline = loop.time()
    last_display = 0.0
    disconnected = False
    try:
        while True:
            try:
                raw = controller.read()
                state = map_to_srm(
                    raw,
                    deadzone=deadzone,
                    trigger_threshold=trigger_threshold,
                )
                if disconnected:
                    print("\nXInput controller reconnected")
                    disconnected = False
            except ControllerDisconnected:
                state = ProControlState()
                if not disconnected:
                    print("\nXInput controller disconnected; sending neutral state")
                    disconnected = True

            await transport.send(encode_pro_control(state, sequence.take()))
            now = loop.time()
            if now - last_display >= 0.2:
                print("\r" + state_line(state, rate), end="", flush=True)
                last_display = now
            deadline += period
            if deadline < now - period:
                deadline = now
            await asyncio.sleep(max(0.0, deadline - loop.time()))
    finally:
        await send_neutral(transport, sequence)
        print()


async def make_transport(args: argparse.Namespace, reporter: IncomingReporter):
    if args.transport == "ble":
        device = await resolve_ble_device(args.device, timeout=args.scan_timeout)
        return BleFfe1Transport(device, reporter.feed)
    return SerialTransport(args.port, args.baudrate, reporter.feed)


async def run_bridge(args: argparse.Namespace) -> None:
    controller = XInputController(args.controller)
    sequence = Sequence()
    reporter = IncomingReporter()
    while True:
        transport: BleFfe1Transport | SerialTransport | None = None
        try:
            transport = await make_transport(args, reporter)
            print(f"Connecting to {transport.description} ...")
            await transport.connect()
            print(f"Connected to {transport.description}; Ctrl+C to stop")
            await stream_controller(
                transport,
                controller,
                sequence,
                rate=args.rate,
                deadzone=args.deadzone,
                trigger_threshold=args.trigger_threshold,
                ble=args.transport == "ble",
            )
        except asyncio.CancelledError:
            raise
        except Exception as error:
            print(f"\nLink error: {error}", file=sys.stderr)
            if args.reconnect_delay <= 0:
                raise
        finally:
            if transport is not None:
                await transport.close()
        print(f"Retrying in {args.reconnect_delay:g} seconds ...")
        await asyncio.sleep(args.reconnect_delay)


async def list_devices(args: argparse.Namespace) -> None:
    print("XInput controllers:")
    for index in range(4):
        controller = XInputController(index)
        try:
            controller.read()
            status = "connected"
        except ControllerDisconnected:
            status = "not connected"
        print(f"  {index}: {status}")
    if args.transport in ("ble", "all"):
        print("BLE devices:")
        devices = await discover_ble(args.scan_timeout)
        if not devices:
            print("  (none found)")
        for device in devices:
            print(f"  {device.name or '(unnamed)':30} {device.address}")
    if args.transport in ("serial", "all"):
        print("Serial ports:")
        ports = serial_ports()
        if not ports:
            print("  (none found)")
        for port, description in ports:
            print(f"  {port:12} {description}")


def bounded_int(minimum: int, maximum: int):
    def parse(value: str) -> int:
        try:
            parsed = int(value)
        except ValueError as error:
            raise argparse.ArgumentTypeError("must be an integer") from error
        if not minimum <= parsed <= maximum:
            raise argparse.ArgumentTypeError(f"must be in {minimum}..{maximum}")
        return parsed

    return parse


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        prog="srm-xbox",
        description="Bridge a native Windows XInput controller to SRM protocol v4 PRO_CONTROL.",
    )
    commands = root.add_subparsers(dest="command", required=True)

    listing = commands.add_parser(
        "list", help="list XInput controllers, BLE devices and/or serial ports"
    )
    listing.add_argument("--transport", choices=("ble", "serial", "all"), default="all")
    listing.add_argument("--scan-timeout", type=float, default=5.0)

    run = commands.add_parser("run", help="start forwarding controller state")
    run.add_argument("--transport", choices=("ble", "serial"), required=True)
    target = run.add_argument_group("target")
    target.add_argument("--device", help="BLE address, exact name, or unique name substring")
    target.add_argument("--port", help="serial port, for example COM10")
    target.add_argument("--baudrate", type=int, default=9600)
    run.add_argument("--controller", type=bounded_int(0, 3), default=0, metavar="0..3")
    run.add_argument("--rate", type=bounded_int(1, 100), default=50, metavar="1..100")
    run.add_argument(
        "--deadzone", type=bounded_int(0, 32766), default=4096, metavar="0..32766"
    )
    run.add_argument(
        "--trigger-threshold", type=bounded_int(1, 255), default=30, metavar="1..255"
    )
    run.add_argument("--scan-timeout", type=float, default=8.0)
    run.add_argument("--reconnect-delay", type=float, default=2.0)
    return root


def validate_args(args: argparse.Namespace, root: argparse.ArgumentParser) -> None:
    if args.command != "run":
        return
    if args.transport == "ble" and not args.device:
        root.error("run --transport ble requires --device")
    if args.transport == "serial" and not args.port:
        root.error("run --transport serial requires --port")
    if args.reconnect_delay < 0:
        root.error("--reconnect-delay cannot be negative")


def main() -> None:
    root = parser()
    args = root.parse_args()
    validate_args(args, root)
    try:
        if args.command == "list":
            asyncio.run(list_devices(args))
        else:
            asyncio.run(run_bridge(args))
    except KeyboardInterrupt:
        print("Stopped")
    except Exception as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
