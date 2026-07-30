import argparse
import unittest

from srm_xbox.cli import bounded_int, parser, validate_args


class CliTests(unittest.TestCase):
    def test_bounded_int_accepts_endpoints(self):
        parse = bounded_int(1, 100)
        self.assertEqual(parse("1"), 1)
        self.assertEqual(parse("100"), 100)

    def test_bounded_int_rejects_outside_range(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            bounded_int(1, 100)("101")

    def test_ble_requires_device_selector(self):
        root = parser()
        args = root.parse_args(["run", "--transport", "ble"])
        with self.assertRaises(SystemExit):
            validate_args(args, root)

    def test_serial_requires_port(self):
        root = parser()
        args = root.parse_args(["run", "--transport", "serial"])
        with self.assertRaises(SystemExit):
            validate_args(args, root)


if __name__ == "__main__":
    unittest.main()
