import asyncio
import unittest

from srm_xbox.gui import BridgeConfig, BridgeThread, Sequence
from srm_xbox.xinput import RawGamepad


class AdvancingController:
    def __init__(self):
        self.read_count = 0

    def read(self):
        self.read_count += 1
        return RawGamepad(
            buttons=0,
            left_trigger=0,
            right_trigger=0,
            left_x=min(self.read_count * 2000, 32767),
            left_y=0,
            right_x=0,
            right_y=0,
        )


class SlowTransport:
    def __init__(self, worker):
        self.worker = worker
        self.sent = []

    async def send(self, data):
        self.sent.append(data)
        await asyncio.sleep(0.08)
        self.worker.stop()


class GuiBridgeTests(unittest.TestCase):
    def test_input_sampling_continues_during_slow_transport_write(self):
        config = BridgeConfig("serial", "COM1", 0, 50, 0, 30, 9600, False)
        worker = BridgeThread(config)
        controller = AdvancingController()
        transport = SlowTransport(worker)

        asyncio.run(worker._stream(transport, controller, Sequence()))

        self.assertEqual(len(transport.sent), 1)
        self.assertGreaterEqual(controller.read_count, 4)
        self.assertGreater(worker.latest_state.left_x, 0)


if __name__ == "__main__":
    unittest.main()
