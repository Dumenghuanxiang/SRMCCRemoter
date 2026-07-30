# -*- mode: python ; coding: utf-8 -*-

from PyInstaller.utils.hooks import collect_submodules

hiddenimports = collect_submodules("bleak.backends.winrt") + collect_submodules("winrt")


def keep_runtime_entry(entry):
    """Drop Qt modules/plugins that this Widgets-only GUI never loads.

    PySide6's hooks conservatively collect the complete Qt plugin tree.  In
    particular, that pulls in software OpenGL, QML/Quick, PDF, SVG, virtual
    keyboard, and every translation even though this application uses only
    QtCore/Gui/Widgets plus the Windows platform and modern Windows style.
    """
    destination = entry[0].replace("\\", "/").lower()
    if not destination.startswith("pyside6/"):
        return True
    if "/translations/" in destination or destination.endswith("/opengl32sw.dll"):
        return False
    if "/plugins/" in destination:
        return destination in {
            "pyside6/plugins/platforms/qwindows.dll",
            "pyside6/plugins/styles/qmodernwindowsstyle.dll",
        }
    removable_prefixes = (
        "pyside6/qt6network.dll",
        "pyside6/qt6opengl.dll",
        "pyside6/qt6pdf.dll",
        "pyside6/qt6qml",
        "pyside6/qt6quick.dll",
        "pyside6/qt6svg.dll",
        "pyside6/qt6virtualkeyboard.dll",
        "pyside6/qtnetwork.pyd",
    )
    return not destination.startswith(removable_prefixes)


def filter_qt_entries(entries):
    return [entry for entry in entries if keep_runtime_entry(entry)]

analysis = Analysis(
    ["gui_main.py"],
    pathex=["src"],
    binaries=[],
    datas=[],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["pytest", "unittest", "tkinter"],
    noarchive=False,
    optimize=1,
)
analysis.binaries = filter_qt_entries(analysis.binaries)
analysis.datas = filter_qt_entries(analysis.datas)
pyz = PYZ(analysis.pure)

exe = EXE(
    pyz,
    analysis.scripts,
    analysis.binaries,
    analysis.datas,
    [],
    name="SRMXbox",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    # UPX is intentionally not required.  The one-file archive is already
    # compressed, and UPX commonly triggers antivirus false positives.
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
