"""Entry point: `python -m jarvis.main [--text]`

--text runs a keyboard chat loop (no microphone/speaker needed) — useful for
testing without audio hardware, or on machines without a mic.
Default mode is voice: say the wake word, then your command.
"""
import argparse
import sys

from jarvis.brain import Jarvis
from jarvis.config import SETTINGS
from jarvis.reminder_watcher import ReminderWatcher


def run_text_mode() -> None:
    jarvis = Jarvis()
    print(f"Jarvis (text mode). Type 'quit' to exit.")

    def announce(text: str) -> None:
        print(f"\n[Jarvis] {text}\n> ", end="", flush=True)

    watcher = ReminderWatcher(announce)
    watcher.start()

    try:
        while True:
            user_text = input("> ").strip()
            if user_text.lower() in {"quit", "exit"}:
                break
            if not user_text:
                continue
            reply = jarvis.ask(user_text)
            print(f"Jarvis: {reply}")
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        watcher.stop()
        print("\nGoodbye.")


def run_voice_mode() -> None:
    from jarvis.voice.listener import Listener
    from jarvis.voice.speaker import Speaker

    jarvis = Jarvis()
    speaker = Speaker()
    listener = Listener()
    watcher = ReminderWatcher(speaker.say)
    watcher.start()

    print(f"Jarvis is listening. Say '{SETTINGS.wake_word}' to begin. Ctrl+C to quit.")
    speaker.say(f"Online and standing by, {SETTINGS.user_name}.")

    try:
        while True:
            listener.wait_for_wake_word()
            speaker.say("Yes?")
            command = listener.listen_for_command()
            if not command:
                speaker.say("I didn't catch that.")
                continue
            print(f"You: {command}")
            reply = jarvis.ask(command)
            print(f"Jarvis: {reply}")
            speaker.say(reply)
    except KeyboardInterrupt:
        pass
    finally:
        watcher.stop()
        speaker.say("Goodbye.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Jarvis personal assistant")
    parser.add_argument("--text", action="store_true", help="Run in text-only mode (no mic/speaker)")
    args = parser.parse_args()

    try:
        if args.text:
            run_text_mode()
        else:
            run_voice_mode()
    except RuntimeError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
