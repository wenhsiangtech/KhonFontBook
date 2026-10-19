import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import newTable
from fontTools.ttLib.tables._f_v_a_r import Axis


class MetadataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="kfb-test-")
        self.path = Path(self.temp.name) / "font.woff2"
        builder = FontBuilder(1000, isTTF=True)
        builder.setupGlyphOrder([".notdef", "A"])
        builder.setupCharacterMap({65: "A"})
        pen = TTGlyphPen(None)
        pen.moveTo((0, 0)); pen.lineTo((200, 700)); pen.lineTo((400, 0)); pen.closePath()
        builder.setupGlyf({".notdef": TTGlyphPen(None).glyph(), "A": pen.glyph()})
        builder.setupHorizontalMetrics({".notdef": (500, 0), "A": (500, 0)})
        builder.setupHorizontalHeader(ascent=800, descent=-200)
        builder.setupNameTable({"familyName": "P2 Test", "styleName": "Medium", "uniqueFontIdentifier": "P2Test", "fullName": "P2 Test Medium", "psName": "P2Test-Medium"})
        builder.setupOS2(usWeightClass=500, fsSelection=0)
        builder.setupPost(italicAngle=0)
        builder.setupMaxp()
        self.font = builder.font
        self.font.flavor = "woff2"

    def tearDown(self):
        self.temp.cleanup()

    def run_parser(self, mutate=None, guard_outlines=False):
        self.font.save(self.path)
        if mutate:
            data = bytearray(self.path.read_bytes()); mutate(data); self.path.write_bytes(data)
        command = [sys.executable, "-I", os.environ["KFB_PARSER"], str(self.path)]
        if guard_outlines:
            # Real WOFF2 metadata must not trigger expensive outline decoding.
            bootstrap = """
import runpy, sys
from fontTools.ttLib import TTFont
original = TTFont.__getitem__
def guarded(self, tag):
    if tag in ('glyf', 'loca', 'CFF ', 'GSUB', 'GPOS'):
        raise AssertionError('Unnecessary outline/layout decode: ' + tag)
    return original(self, tag)
TTFont.__getitem__ = guarded
sys.argv = sys.argv[1:]
runpy.run_path(sys.argv[0], run_name='__main__')
"""
            command = [sys.executable, "-I", "-c", bootstrap,
                       os.environ["KFB_PARSER"], str(self.path)]
        result = subprocess.run(command, capture_output=True, text=True, timeout=20)
        return result.returncode, json.loads(result.stdout)

    def test_metadata_does_not_decode_outlines_or_layout(self):
        code, data = self.run_parser(guard_outlines=True)
        self.assertEqual(code, 0, data)
        self.assertEqual(data["weight"], 500)
        self.assertEqual(data["coverageRanges"], [{"start": 65, "end": 65}])

    def test_static_metadata_is_real(self):
        code, data = self.run_parser()
        self.assertEqual(code, 0)
        self.assertEqual((data["family"], data["weight"], data["style"]), ("P2 Test", 500, "normal"))
        self.assertEqual(data["coverageRanges"], [{"start": 65, "end": 65}])

    def test_non_font(self):
        code, data = self.run_parser(lambda b: b.__setitem__(slice(0, 4), b"fake"))
        self.assertNotEqual(code, 0); self.assertEqual(data["error"], "INVALID_FONT")

    def test_ttf_metadata(self):
        self.font.flavor = None
        code, data = self.run_parser()
        self.assertEqual(code, 0, data)
        self.assertEqual(data["weight"], 500)

    def test_ttf_truncated_table_is_rejected(self):
        self.font.flavor = None
        code, data = self.run_parser(lambda b: b.__delitem__(slice(-40, None)))
        self.assertNotEqual(code, 0)
        self.assertEqual(data["error"], "INVALID_FONT")

    def test_oblique_angle_in_both_formats_and_independent_italic_flag(self):
        for flavor in (None, "woff2"):
            for italic in (False, True):
                with self.subTest(flavor=flavor, italic=italic):
                    self.font.flavor = flavor
                    self.font["OS/2"].version = 4
                    self.font["OS/2"].fsSelection = (1 << 9) | int(italic)
                    self.font["head"].macStyle = 2 if italic else 0
                    self.font["post"].italicAngle = -12
                    code, data = self.run_parser()
                    self.assertEqual(code, 0, data)
                    self.assertEqual(data["style"], "oblique 12deg")

    def test_italic_remains_distinct(self):
        self.font["OS/2"].fsSelection = 1
        self.font["head"].macStyle = 2
        self.font["post"].italicAngle = -10
        code, data = self.run_parser()
        self.assertEqual(code, 0, data)
        self.assertEqual(data["style"], "italic")

    def test_truncated_font(self):
        code, data = self.run_parser(lambda b: b.__delitem__(slice(50, None)))
        self.assertNotEqual(code, 0); self.assertEqual(data["error"], "INVALID_FONT")

    def test_expanded_limit_before_decoding(self):
        code, data = self.run_parser(lambda b: b.__setitem__(slice(16, 20), struct.pack(">I", 64 * 1024 * 1024 + 1)))
        self.assertNotEqual(code, 0); self.assertEqual(data["error"], "EXPANDED_TOO_LARGE")

    def test_variable_font_rejected(self):
        table = newTable("fvar")
        axis = Axis(); axis.axisTag = "wght"; axis.minValue = 100; axis.defaultValue = 500; axis.maxValue = 900; axis.flags = 0; axis.axisNameID = 256
        table.axes = [axis]; table.instances = []; self.font["fvar"] = table
        code, data = self.run_parser()
        self.assertNotEqual(code, 0); self.assertEqual(data["error"], "UNSUPPORTED_FONT")

    def test_conflicting_italic_metadata_rejected(self):
        self.font["OS/2"].fsSelection |= 1
        code, data = self.run_parser()
        self.assertNotEqual(code, 0); self.assertEqual(data["error"], "INVALID_FONT")


if __name__ == "__main__":
    unittest.main()
