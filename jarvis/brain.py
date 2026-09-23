"""The assistant's brain: a Claude conversation loop with tool use."""
from typing import Callable, Optional

from anthropic import Anthropic

from jarvis.config import SETTINGS
from jarvis.tools import TOOLS, run_tool

SYSTEM_PROMPT_TEMPLATE = """You are Jarvis, a witty, imperturbably composed personal AI assistant \
in the mold of Tony Stark's J.A.R.V.I.S. You address the user as "{user_name}". \
Keep spoken responses concise (a sentence or two) since they may be read aloud by \
text-to-speech — save detail for when the user asks for it. Use your tools whenever \
a question needs current information, or the user asks you to manage tasks, \
reminders, notes, or their system, rather than guessing. Never invent tool results."""


class Jarvis:
    def __init__(
        self,
        tools: Optional[list[dict]] = None,
        tool_runner: Optional[Callable[[str, dict], str]] = None,
        system_prompt: Optional[str] = None,
    ):
        SETTINGS.require_api_key()
        self.client = Anthropic(api_key=SETTINGS.anthropic_api_key)
        self.model = SETTINGS.model
        self.system_prompt = system_prompt or SYSTEM_PROMPT_TEMPLATE.format(user_name=SETTINGS.user_name)
        self.tools = tools if tools is not None else TOOLS
        self.tool_runner = tool_runner
        self.history: list[dict] = []

    def reset(self):
        self.history = []

    def ask(self, user_text: str, context: str = "") -> str:
        """Send one user turn and run the tool loop until Claude answers in text.

        `context` is appended to the system prompt for this call only (e.g. the
        phone's local time), so it never piles up in the conversation history.
        """
        runner = self.tool_runner or run_tool
        system = f"{self.system_prompt}\n\n{context}" if context else self.system_prompt
        self.history.append({"role": "user", "content": user_text})

        while True:
            response = self.client.messages.create(
                model=self.model,
                max_tokens=1024,
                system=system,
                tools=self.tools,
                messages=self.history,
            )
            self.history.append({"role": "assistant", "content": response.content})

            if response.stop_reason != "tool_use":
                return "".join(
                    block.text for block in response.content if block.type == "text"
                ).strip()

            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    result = runner(block.name, block.input)
                    tool_results.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": result,
                        }
                    )
            self.history.append({"role": "user", "content": tool_results})
