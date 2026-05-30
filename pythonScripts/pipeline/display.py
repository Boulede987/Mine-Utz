def section(title: str = "") -> None:
    width = 60
    if title:
        left_pad = (width - len(title) - 2) // 2
        print(f"\n{'=' * left_pad} {title} {'=' * (width - left_pad - len(title) - 2)}")
    else:
        print("\n" + "=" * width)


def pause(instruction: str) -> None:
    print(f"\n  [ACTION REQUIRED]  {instruction}")
    input("  Press Enter when done...")


def build_menu(steps: list) -> str:
    lines = [
        "",
        "  ╔══════════════════════════════════════╗",
        "  ║      Mineutze Pipeline Runner        ║",
        "  ╚══════════════════════════════════════╝",
    ]
    for key, label, _ in steps:
        if key is None:
            dashes = "─" * max(0, 37 - len(label))
            lines.append(f"\n  ── {label} {dashes}")
        else:
            lines.append(f"  {key:>2}  {label}")
    lines.append("\n   0  Exit")
    return "\n".join(lines)
