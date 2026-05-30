from .actions import STEPS
from .display import build_menu

QUIT = "0"


def _find_action(choice: str):
    for key, _, action in STEPS:
        if key == choice and action is not None:
            return action
    return None


def run() -> None:
    choice = ""
    while choice != QUIT:
        print(build_menu(STEPS))
        choice = input("  Choice: ").strip()
        if choice == QUIT:
            print("  Bye.")
            return
        action = _find_action(choice)
        if action is not None:
            action()
            input("\n  Press Enter to return to menu...")
        else:
            print("  Unknown option.")
