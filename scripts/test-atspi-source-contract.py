#!/usr/bin/env python3
"""Validate the private AT-SPI service's embedded D-Bus contract."""

import ast
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "native/archphene-portal/archphene_atspi_bridge.c"
CLIENT_SOURCE = ROOT / "native/archphene-portal/archphene_atspi_client.c"
PUBLISH_SOURCE = ROOT / "native/archphene-portal/archphene_atspi_publish.c"
TRANSLATOR_SOURCE = ROOT / "native/archphene-portal/archphene_atspi_translator.c"
ANDROID_SOURCE = ROOT / "prototypes/shared-android-bridge/src/org/archphene/bridge/ArchpheneAccessibilityBridge.java"
ANDROID_BROKER_SOURCE = ROOT / "prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidCapabilityBroker.java"
ANDROID_ACTIVITY_SOURCE = ROOT / "prototypes/shared-android-bridge/src/org/archphene/bridge/ArchpheneCompositorActivity.java"
ANDROID_SESSION_SOURCE = ROOT / "prototypes/shared-android-bridge/src/org/archphene/bridge/ArchpheneCompositorSession.java"
ANDROID_CAPABILITY_SOURCE = ROOT / "native/archphene-android-capability/archphene_android.c"
ACCESSIBILITY_PROBE_SOURCE = (
    ROOT
    / "prototypes/accessibility-capability-probe/src/org/archphene/bridge"
    / "ProbeAccessibilityService.java"
)


def c_string(name: str, source: str) -> str:
    match = re.search(
        rf"static const char {re.escape(name)}\[\]\s*=\s*(.*?);\s*\n",
        source,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"missing C string {name}")
    fragments = re.findall(r'"(?:\\.|[^"\\])*"', match.group(1))
    if not fragments:
        raise AssertionError(f"empty C string {name}")
    return "".join(ast.literal_eval(fragment) for fragment in fragments)


def interface(root: ET.Element, name: str) -> ET.Element:
    value = root.find(f"./interface[@name='{name}']")
    if value is None:
        raise AssertionError(f"missing interface {name}")
    return value


def method_signature(node: ET.Element, name: str) -> tuple[str, str]:
    method = node.find(f"./method[@name='{name}']")
    if method is None:
        raise AssertionError(f"missing method {node.get('name')}.{name}")
    inputs = "".join(
        argument.get("type", "")
        for argument in method.findall("arg")
        if argument.get("direction", "in") == "in"
    )
    outputs = "".join(
        argument.get("type", "")
        for argument in method.findall("arg")
        if argument.get("direction") == "out"
    )
    return inputs, outputs


def signal_signature(node: ET.Element, name: str) -> str:
    signal = node.find(f"./signal[@name='{name}']")
    if signal is None:
        raise AssertionError(f"missing signal {node.get('name')}.{name}")
    return "".join(argument.get("type", "") for argument in signal.findall("arg"))


def require(actual: object, expected: object, label: str) -> None:
    if actual != expected:
        raise AssertionError(f"{label}: expected {expected!r}, got {actual!r}")


def validate_role_constants(client_source: str) -> None:
    expected = {
        "ROLE_ALERT": 2,
        "ROLE_CHECK_BOX": 7,
        "ROLE_CHECK_MENU_ITEM": 8,
        "ROLE_COMBO_BOX": 11,
        "ROLE_DIAL": 15,
        "ROLE_DIALOG": 16,
        "ROLE_FRAME": 23,
        "ROLE_ICON": 26,
        "ROLE_IMAGE": 27,
        "ROLE_INTERNAL_FRAME": 28,
        "ROLE_LABEL": 29,
        "ROLE_LIST": 31,
        "ROLE_LIST_ITEM": 32,
        "ROLE_MENU": 33,
        "ROLE_MENU_BAR": 34,
        "ROLE_MENU_ITEM": 35,
        "ROLE_PAGE_TAB": 37,
        "ROLE_PAGE_TAB_LIST": 38,
        "ROLE_PASSWORD_TEXT": 40,
        "ROLE_POPUP_MENU": 41,
        "ROLE_PROGRESS_BAR": 42,
        "ROLE_BUTTON": 43,
        "ROLE_RADIO_BUTTON": 44,
        "ROLE_RADIO_MENU_ITEM": 45,
        "ROLE_SCROLL_BAR": 48,
        "ROLE_SLIDER": 51,
        "ROLE_SPIN_BUTTON": 52,
        "ROLE_TABLE": 55,
        "ROLE_TABLE_CELL": 56,
        "ROLE_TEAROFF_MENU_ITEM": 59,
        "ROLE_TEXT": 61,
        "ROLE_TOGGLE_BUTTON": 62,
        "ROLE_TREE": 65,
        "ROLE_TREE_TABLE": 66,
        "ROLE_WINDOW": 69,
        "ROLE_PARAGRAPH": 73,
        "ROLE_APPLICATION": 75,
        "ROLE_EDITBAR": 77,
        "ROLE_ENTRY": 79,
        "ROLE_HEADING": 83,
        "ROLE_LINK": 88,
        "ROLE_TABLE_ROW": 90,
        "ROLE_TREE_ITEM": 91,
        "ROLE_DOCUMENT_TEXT": 94,
        "ROLE_LIST_BOX": 98,
        "ROLE_LEVEL_BAR": 103,
        "ROLE_STATIC": 116,
        "ROLE_DESCRIPTION_LIST": 121,
        "ROLE_DESCRIPTION_TERM": 122,
        "ROLE_DESCRIPTION_VALUE": 123,
        "ROLE_PUSH_BUTTON_MENU": 129,
        "ROLE_SWITCH": 130,
    }
    for name, value in expected.items():
        match = re.search(rf"\b{re.escape(name)}\s*=\s*(\d+)", client_source)
        if match is None or int(match.group(1)) != value:
            actual = None if match is None else int(match.group(1))
            raise AssertionError(
                f"AT-SPI role {name}: expected {value}, got {actual}"
            )

def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    client_source = CLIENT_SOURCE.read_text(encoding="utf-8")
    publish_source = PUBLISH_SOURCE.read_text(encoding="utf-8")
    translator_source = TRANSLATOR_SOURCE.read_text(encoding="utf-8")
    android_source = ANDROID_SOURCE.read_text(encoding="utf-8")
    android_broker_source = ANDROID_BROKER_SOURCE.read_text(encoding="utf-8")
    android_activity_source = ANDROID_ACTIVITY_SOURCE.read_text(encoding="utf-8")
    android_session_source = ANDROID_SESSION_SOURCE.read_text(encoding="utf-8")
    android_capability_source = ANDROID_CAPABILITY_SOURCE.read_text(encoding="utf-8")
    accessibility_probe_source = ACCESSIBILITY_PROBE_SOURCE.read_text(encoding="utf-8")
    validate_role_constants(client_source)
    bus_root = ET.fromstring(c_string("bus_xml", source))
    socket_root = ET.fromstring(c_string("root_xml", source))
    registry_root = ET.fromstring(c_string("registry_xml", source))

    bus = interface(bus_root, "org.a11y.Bus")
    require(method_signature(bus, "GetAddress"), ("", "s"), "Bus.GetAddress")

    socket = interface(socket_root, "org.a11y.atspi.Socket")
    require(method_signature(socket, "Embed"), ("(so)", "(so)"), "Socket.Embed")
    require(method_signature(socket, "Embedded"), ("s", ""), "Socket.Embedded")
    require(method_signature(socket, "Unembed"), ("(so)", ""), "Socket.Unembed")
    require(signal_signature(socket, "Available"), "(so)", "Socket.Available")

    if socket_root.find("./interface[@name='org.a11y.atspi.Registry']") is not None:
        raise AssertionError("registry interface exposed on accessible root")
    if registry_root.find("./interface[@name='org.a11y.atspi.Socket']") is not None:
        raise AssertionError("socket interface exposed on registry path")
    registry = interface(registry_root, "org.a11y.atspi.Registry")
    require(method_signature(registry, "RegisterEvent"), ("sass", ""), "Registry.RegisterEvent")
    require(method_signature(registry, "DeregisterEvent"), ("ss", ""), "Registry.DeregisterEvent")
    require(method_signature(registry, "GetRegisteredEvents"), ("", "a(ss)"), "Registry.GetRegisteredEvents")
    require(
        signal_signature(registry, "EventListenerRegistered"),
        "ssas",
        "Registry.EventListenerRegistered",
    )
    require(
        signal_signature(registry, "EventListenerDeregistered"),
        "ss",
        "Registry.EventListenerDeregistered",
    )

    dispatch_tokens = (
        'strcmp(path, A11Y_BUS_PATH) == 0',
        'strcmp(path, REGISTRY_ROOT) == 0',
        'REGISTRY_ROOT, A11Y_SOCKET, "Available"',
        '? root_xml : registry_xml',
        'require_signature(connection, message, "ssv", NULL, NULL)',
        'require_signature(connection, message, "(so)", NULL, NULL)',
        'require_signature(connection, message, "sass", "sas", "s")',
        'require_signature(connection, message, "ss", "s", NULL)',
        "read_boolean_property_value(request)",
        'dbus_message_has_signature(message, "siiva{sv}")',
        'dbus_message_has_signature(message, "sss")',
    )
    for token in dispatch_tokens:
        if token not in source:
            raise AssertionError(f"missing dispatch contract: {token}")

    if not re.search(
        r"static dbus_bool_t send_available\(.*?"
        r"const char \*owner = A11Y_REGISTRY;",
        source,
        re.DOTALL,
    ):
        raise AssertionError("Available must advertise the well-known registry name")
    if not re.search(
        r"static void handle_embed\(.*?"
        r"const char \*owner = dbus_bus_get_unique_name\(connection\);",
        source,
        re.DOTALL,
    ):
        raise AssertionError("Embed must return the registry connection unique name")

    client_tokens = (
        'exact_reply(reply, "v")',
        'exact_reply(reply, "u")',
        'exact_reply(reply, "as")',
        'exact_reply(reply, "au")',
        'exact_reply(reply, "(iiii)")',
        'exact_reply(reply, "a(sss)")',
        'exact_reply(reply, "a(so)")',
        'exact_reply(reply, "b")',
        "dbus_validate_bus_name(reference->bus, NULL)",
        "dbus_validate_path(reference->path, NULL)",
        "static size_t utf8_sequence_length",
        "read_method_uint32(",
        'ACCESSIBLE, "GetRole", &role_id',
        "role_id == ROLE_APPLICATION",
        "role_id == ROLE_MENU_BAR",
        "dbus_bool_t complete = index == 2",
        "*children_count = 0;",
        "int32_t end = ARCHPHENE_ATSPI_TEXT_MAX;",
        "interfaces.text && !node->password",
        "return truncated ? 1 : 0;",
        "STATE_SHOWING = 25",
        "STATE_VISIBLE = 30",
        "node->showing = has_state(states, STATE_SHOWING)",
        "node->visible = has_state(states, STATE_VISIBLE)",
        'strcmp(normalized, "showmenu") == 0',
        "node->show_menu_action = TRUE",
    )
    for token in client_tokens:
        if token not in client_source:
            raise AssertionError(f"missing client validation: {token}")
    if "strcmp(child.bus, reference->bus)" in client_source:
        raise AssertionError("cross-process AT-SPI children are still rejected")

    lifecycle_tokens = (
        (publish_source, "ARCHPHENE_ATSPI_TREE_TRUNCATED", "bounded-tree publication"),
        (publish_source, "if (!node.showing || !node.visible) continue;", "hidden-tree pruning"),
        (publish_source, "if (tree->count == 0) goto fail;", "pre-free empty-tree check"),
        (publish_source, "if (read_result < 0) {\n            truncated = 1;",
         "unreadable transient child isolation"),
        (publish_source, "size_t checkpoint = output.length;", "bounded JSON prefix"),
        (publish_source, "output.length = checkpoint;", "JSON overflow rollback"),
        (publish_source, "if (emitted == 0) output.failed = 1;", "non-empty JSON prefix"),
        (translator_source, "#define REBUILD_RETRY_MILLIS 250", "retry backoff"),
        (translator_source, "#define MAX_TRANSIENT_ROOTS 32", "bounded transient roots"),
        (translator_source, "transient_change = enabled != 0 ? 1 : -1", "popup visibility roots"),
        (translator_source, "state.transient_generation++", "popup generation tracking"),
        (translator_source, "bool menu_click =", "semantic menu fallback"),
        (translator_source, "parent_is_menu_bar(state.tree, id)", "menu-bar child fallback"),
        (translator_source, "menu_bar_click ? activate_menu_pointer",
         "serial-producing menu-bar activation"),
        (translator_source, "remove_transient_bus_locked(bus)", "transient disconnect cleanup"),
        (translator_source,
         "build_result < 0 || build_result == ARCHPHENE_ATSPI_TREE_RETRY",
         "transient-tree snapshot retention"),
        (translator_source,
         "rebuild_result == ARCHPHENE_ATSPI_TREE_RETRY",
         "bounded-tree retry isolation"),
        (translator_source, "if (!found_node) return;", "action lookup lifetime"),
        (translator_source,
         "state.tree->nodes[index].node.reference.bus",
         "delegated accessibility bus ownership"),
        (translator_source, "static int base64url_value", "base64url action text"),
        (translator_source, "remainder == 1", "base64url length validation"),
        (translator_source, "(second & 0x0f) != 0", "base64url trailing bits"),
        (translator_source, "byte == '\\0'", "embedded-NUL rejection"),
        (translator_source, "dbus_validate_utf8(output, NULL)", "UTF-8 action text"),
        (translator_source,
         "strchr(*encoded_text, '\\t') != NULL",
         "exact action field count"),
    )
    for implementation, token, label in lifecycle_tokens:
        if token not in implementation:
            raise AssertionError(f"missing {label}: {token}")
    for forbidden in ("value == '+'", "value == '/'", "encoded[index] == '='"):
        if forbidden in translator_source:
            raise AssertionError(f"non-canonical base64url accepted: {forbidden}")

    android_tokens = (
        "semanticWindowAssignments",
        "stickyAssignments.keySet().removeIf",
        "Math.abs((long)width - window.width)",
        "image.getScaleType() == ImageView.ScaleType.FIT_CENTER",
        "float uniform = Math.min(scaleX, scaleY)",
        "source.left - viewportLeft",
        "source.top - viewportTop",
        "descriptor.title.equals(node.windowTitle)",
        "text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT",
        "manager == null || !manager.isEnabled()",
        "catch (IllegalStateException accessibilityDisabled)",
    )
    for token in android_tokens:
        if token not in android_source:
            raise AssertionError(f"missing Android semantic ownership: {token}")
    if "payload.substring(0, payloadEnd).split" not in accessibility_probe_source:
        raise AssertionError("empty accessibility command values are not preserved")

    fallback_tokens = (
        (android_source, "void setMenuFallback", "Android fallback registration"),
        (android_source, "void activateMenuFallback", "Android fallback activation"),
        (android_broker_source, "ACCESSIBILITY_MENU_FALLBACK", "broker fallback command"),
        (android_activity_source, "this::activateAccessibilityMenu", "compositor menu activation"),
        (android_source, "root.bridgeForNode(nodeId)", "menu semantic window ownership"),
        (android_source, "bounds.exactCenterX()", "scaled menu target center"),
        (android_activity_source, "session.pointerClick(", "pointer menu activation"),
        (android_session_source, "public void pointerClick(", "host-coordinate pointer activation"),
        (android_session_source, "POINTER_MOTION, mappedX, mappedY", "menu pointer motion"),
        (android_session_source, "POINTER_BUTTON, 1", "menu pointer press"),
        (android_session_source, "POINTER_BUTTON, 0", "menu pointer release"),
        (android_session_source, "ACCESSIBILITY_TOUCH_ID", "reserved accessibility touch slot"),
        (android_capability_source,
         "archphene_android_accessibility_menu_fallback",
         "native fallback request"),
    )
    for implementation, token, label in fallback_tokens:
        if token not in implementation:
            raise AssertionError(f"missing {label}: {token}")
    action_body = re.search(
        r"public boolean performAction\(.*?\n    private void sendEvent",
        android_source,
        re.DOTALL,
    )
    if action_body is None or "bridgeForNode" in action_body.group(0):
        raise AssertionError("Android accessibility actions cross window ownership")

    print("Private AT-SPI introspection and dispatch contract passed")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ET.ParseError, OSError, SyntaxError) as error:
        print(f"AT-SPI source contract failed: {error}", file=sys.stderr)
        raise SystemExit(1)
