"""Nederlandstalige spraakassistent op basis van Mistral Voxtral.

Deze module verbindt vier verantwoordelijkheden tot één gespreksloop:

1. Spraakdetectie en -opname met Silero VAD (Voice Activity Detection).
2. Spraak-naar-tekst (STT) via het Mistral-transcriptiemodel.
3. Een streamend antwoord ophalen bij een externe chat-API.
4. Tekst-naar-spraak (TTS) afspelen, optioneel met "barge-in" zodat de
   gebruiker het antwoord kan onderbreken met een nieuwe vraag.

De configuratie is gebundeld in :class:`Config`, terwijl de gespreksstroom is
ondergebracht in :class:`VoiceAssistant`. Pure hulpfuncties (zonder neveneffect)
staan los zodat ze eenvoudig te testen en te hergebruiken zijn.
"""

from __future__ import annotations

import base64
import io
import json
import os
import re
import time
from collections import deque
from dataclasses import dataclass

import numpy as np
import requests
import sounddevice as sd
import soundfile as sf
import torch
from mistralai.client import Mistral
from silero_vad import VADIterator, load_silero_vad

# Type-alias voor het geladen Silero VAD-model. Het concrete type wordt door de
# bibliotheek bepaald; ``object`` houdt de annotatie leesbaar én correct.
VadModel = object


@dataclass(frozen=True)
class Config:
    """Onveranderlijke configuratie voor de spraakassistent.

    Door alle instelbare waarden te centraliseren is gedrag eenvoudig aan te
    passen zonder de logica te wijzigen (onderhoudbaarheid, ISO 25010).
    """

    # Connectoren in-/uitschakelen.
    use_google_calendar: bool = True
    use_google_gmail: bool = True

    # Externe chat-API.
    chat_api_url: str = "http://10.179.224.51:8080/chat"
    unavailable_text: str = "Ik ben niet bereikbaar."
    chat_connect_timeout_s: float = 5.0
    chat_read_timeout_s: float = 60.0

    # Audio-opname en VAD.
    sample_rate: int = 16000
    block_ms: int = 32

    # Mistral-modellen en stem.
    stt_model: str = "voxtral-mini-2602"
    tts_model: str = "voxtral-mini-tts-2603"
    tts_voice_id: str = "5940190b-f58a-4c3e-8264-a40d63fd6883"

    # Logging.
    enable_print_statements: bool = True
    times_file: str = "times.txt"

    # Barge-in (antwoord onderbreken met nieuwe spraak).
    enable_barge_in: bool = False
    barge_in_cooldown_ms: int = 700
    barge_in_threshold: float = 0.86
    barge_in_min_silence_ms: int = 700
    barge_in_energy_gate_db: float = -28.0
    barge_in_confirm_ms: int = 320
    barge_in_confirm_hits: int = 3
    barge_in_speech_pad_ms: int = 120

    # Drempels voor het uitspreken van streamende tekst.
    min_first_chunk_chars: int = 12
    min_buffer_chars_without_punctuation: int = 40


# Eén gedeelde configuratie-instantie voor de hele module.
CONFIG = Config()


def log(*args: object, **kwargs: object) -> None:
    """Print alleen wanneer logging in de configuratie is ingeschakeld."""
    if CONFIG.enable_print_statements:
        print(*args, **kwargs)


class ResponseTimer:
    """Meet de latency tussen het einde van de spraak en de start van TTS.

    Vervangt de eerdere globale toestand door een expliciet, herbruikbaar object
    (betere testbaarheid en geen verborgen neveneffecten).
    """

    def __init__(self) -> None:
        self._speech_end_time: float | None = None

    def mark_speech_end(self) -> None:
        """Leg het moment vast waarop de gebruiker is uitgesproken."""
        self._speech_end_time = time.time()

    def take_elapsed_ms(self) -> float | None:
        """Geef de verstreken tijd (ms) terug en reset de timer.

        Retourneert ``None`` wanneer er geen spraak-eindmoment is vastgelegd.
        """
        if self._speech_end_time is None:
            return None
        elapsed_ms = (time.time() - self._speech_end_time) * 1000
        self._speech_end_time = None
        return elapsed_ms


def write_response_time(response_time_ms: float) -> None:
    """Schrijf de responstijd naar het tijdenbestand als logging uit staat."""
    if CONFIG.enable_print_statements:
        return
    try:
        with open(CONFIG.times_file, "a", encoding="utf-8") as time_file:
            time_file.write(f"{response_time_ms:.2f}ms\n")
    except OSError as error:
        log(f"❌ Fout bij schrijven naar {CONFIG.times_file}: {error}")


# --- Pure hulpfuncties (geen neveneffecten) -------------------------------- #


def rms_dbfs(chunk: np.ndarray) -> float:
    """Bereken het volume van een audioblok in dBFS (decibel full scale)."""
    rms = float(np.sqrt(np.mean(np.square(chunk))))
    if rms <= 1e-12:
        return -120.0
    return 20.0 * np.log10(rms)


def passes_barge_in_energy_gate(chunk: np.ndarray) -> bool:
    """Bepaal of een audioblok luid genoeg is om barge-in te overwegen."""
    return rms_dbfs(chunk) >= CONFIG.barge_in_energy_gate_db


def normalize_chunk(raw_chunk: np.ndarray) -> np.ndarray:
    """Maak een mono float32-array van een ruwe opname en voorkom 0-d arrays."""
    chunk = np.squeeze(raw_chunk).astype(np.float32)
    if chunk.ndim == 0:
        chunk = np.array([chunk], dtype=np.float32)
    return chunk


def extract_stream_text(data_obj: object) -> str:
    """Haal de tekstinhoud uit één SSE-payload van uiteenlopende API-formaten."""
    if isinstance(data_obj, str):
        return data_obj

    if not isinstance(data_obj, dict):
        return ""

    # OpenAI-achtige streaming payload.
    choices = data_obj.get("choices")
    if isinstance(choices, list) and choices:
        first_choice = choices[0]
        if isinstance(first_choice, dict):
            delta = first_choice.get("delta")
            if isinstance(delta, dict):
                content = delta.get("content")
                if isinstance(content, str):
                    return content
            message = first_choice.get("message")
            if isinstance(message, dict):
                content = message.get("content")
                if isinstance(content, str):
                    return content

    # Spring AI of eenvoudiger, niet-streamende structuren.
    result = data_obj.get("result")
    if isinstance(result, dict):
        output = result.get("output")
        if isinstance(output, dict):
            content = output.get("content")
            if isinstance(content, str):
                return content

    content = data_obj.get("content")
    if isinstance(content, str):
        return content

    return ""


def pop_speakable_text(buffer: str) -> tuple[str, str]:
    """Splits uitspreekbare tekst af van de rest van de streambuffer.

    Geeft ``(uitspreekbaar, resterend)`` terug. Er wordt geknipt op de laatste
    zinsafsluiting, of - als de buffer groot genoeg is - op de laatste spatie.
    """
    matches = list(re.finditer(r"[.!?]", buffer))
    if matches:
        cut_index = matches[-1].end()
        ready = buffer[:cut_index].strip()
        remaining = buffer[cut_index:].lstrip()
        return ready, remaining

    # Spreek ook zonder punctuatie zodra de buffer groot genoeg is.
    if len(buffer) >= CONFIG.min_buffer_chars_without_punctuation:
        cut_index = buffer.rfind(" ")
        if cut_index > 0:
            ready = buffer[:cut_index].strip()
            remaining = buffer[cut_index + 1:].lstrip()
            return ready, remaining

    return "", buffer


# --- Connectoren (simulatie) ----------------------------------------------- #


def trigger_google_calendar(prompt: str) -> str:
    """Simuleer het aanmaken van een agenda-afspraak."""
    log(f"[Google Calendar] Actie: {prompt}")
    return "Afspraak toegevoegd aan Google Calendar."


def trigger_google_gmail(prompt: str) -> str:
    """Simuleer het versturen van een e-mail."""
    log(f"[Google Gmail] Actie: {prompt}")
    return "E-mail verzonden via Gmail."


def detect_intent(transcript: str) -> tuple[str | None, str]:
    """Bepaal de intentie van een transcript op basis van trefwoorden."""
    transcript_lower = transcript.lower()

    if CONFIG.use_google_calendar and re.search(
        r"\b(maak|plan|voeg toe|afspraak|meeting|agenda|calendar)\b",
        transcript_lower,
    ):
        return "google_calendar", transcript

    if CONFIG.use_google_gmail and re.search(
        r"\b(stuur|verstuur|email|mail|e-mail|verzend)\b",
        transcript_lower,
    ):
        return "google_gmail", transcript

    return None, transcript


class VoiceAssistant:
    """Coördineert opname, transcriptie, chat-stream en spraakuitvoer.

    Door de gedeelde afhankelijkheden (Mistral-client, VAD-model en timer) als
    objecttoestand te bewaren, hoeven ze niet door elke functie te worden
    doorgegeven. Dat verhoogt de cohesie en verlaagt de koppeling.
    """

    def __init__(self, client: Mistral, vad_model: VadModel) -> None:
        self._client = client
        self._vad_model = vad_model
        self._timer = ResponseTimer()
        self._listen_vad = VADIterator(
            vad_model,
            threshold=0.5,
            sampling_rate=CONFIG.sample_rate,
            min_silence_duration_ms=700,
            speech_pad_ms=200,
        )

    # --- Spraakopname + VAD ------------------------------------------------ #

    def _record_utterance(self, vad_iterator: VADIterator) -> np.ndarray:
        """Neem op tot Silero VAD het einde van een spraaksegment detecteert."""
        log("🎤 Luisteren... spreek nu (Ctrl+C om te stoppen).")

        block_size = int(CONFIG.sample_rate * CONFIG.block_ms / 1000)
        preroll_blocks = max(1, int(0.4 * CONFIG.sample_rate / block_size))
        preroll: deque[np.ndarray] = deque(maxlen=preroll_blocks)

        speech_active = False
        chunks: list[np.ndarray] = []

        with sd.InputStream(
            samplerate=CONFIG.sample_rate,
            channels=1,
            dtype="float32",
            blocksize=block_size,
        ) as stream:
            while True:
                raw_chunk, _ = stream.read(block_size)
                chunk = normalize_chunk(raw_chunk)

                event = vad_iterator(torch.from_numpy(chunk), return_seconds=False)

                if not speech_active:
                    preroll.append(chunk)
                    if event and "start" in event:
                        speech_active = True
                        chunks = list(preroll)
                        preroll.clear()
                        log("🟢 Spraak gedetecteerd...")
                else:
                    chunks.append(chunk)
                    if event and "end" in event:
                        log("🔵 Spraak beëindigd, versturen naar transcriptie...")
                        self._timer.mark_speech_end()
                        vad_iterator.reset_states()
                        return np.concatenate(chunks)

    # --- Speech-to-Text ---------------------------------------------------- #

    def _transcribe(self, audio_data: np.ndarray) -> str | None:
        """Zet opgenomen audio om naar tekst via het Mistral STT-model."""
        try:
            audio_buffer = io.BytesIO()
            sf.write(audio_buffer, audio_data, CONFIG.sample_rate, format="WAV")
            audio_buffer.seek(0)

            response = self._client.audio.transcriptions.complete(
                model=CONFIG.stt_model,
                file={
                    "content": audio_buffer.getvalue(),
                    "file_name": "temp_audio.wav",
                },
                language="nl",
            )

            transcribed_text = response.text
            log(f"🗣️ Jij zei: {transcribed_text}")
            return transcribed_text

        except Exception as error:  # noqa: BLE001 - externe API kan diverse fouten gooien
            log(f"❌ Fout bij transcriptie: {error}")
            return None

    # --- Text-to-Speech ---------------------------------------------------- #

    def _speak(self, text: str) -> bool:
        """Synthetiseer ``text`` en speel het af; retourneer of er barge-in was."""
        try:
            response = self._client.audio.speech.complete(
                model=CONFIG.tts_model,
                input=text,
                voice_id=CONFIG.tts_voice_id,
                response_format="wav",
            )

            # Volgens de Mistral-docs is de audio base64-gecodeerd in audio_data.
            audio_bytes = base64.b64decode(response.audio_data)
            tts_buffer = io.BytesIO(audio_bytes)
            tts_buffer.seek(0)
            audio_data, sample_rate = sf.read(tts_buffer, dtype="float32")

            self._report_response_time()
            return self._play_with_barge_in(audio_data, sample_rate)

        except Exception as error:  # noqa: BLE001 - externe API kan diverse fouten gooien
            log(f"❌ Fout bij TTS: {error}")
            return False

    def _report_response_time(self) -> None:
        """Log en bewaar de responstijd sinds het einde van de spraak."""
        elapsed_ms = self._timer.take_elapsed_ms()
        if elapsed_ms is not None:
            log(f"⏱️ Responstijd: {elapsed_ms:.2f}ms")
            write_response_time(elapsed_ms)

    def _play_with_barge_in(
        self,
        audio_data: np.ndarray,
        playback_sample_rate: int,
    ) -> bool:
        """Speel audio af en luister naar barge-in wanneer dit is ingeschakeld.

        Retourneert ``True`` zodra de gebruiker het antwoord onderbreekt met
        bevestigde spraak, anders ``False``.
        """
        if self._vad_model is None or not CONFIG.enable_barge_in:
            sd.play(audio_data, playback_sample_rate)
            sd.wait()
            return False

        duration_sec = len(audio_data) / float(playback_sample_rate)
        block_size = int(CONFIG.sample_rate * CONFIG.block_ms / 1000)

        barge_in_vad = VADIterator(
            self._vad_model,
            threshold=CONFIG.barge_in_threshold,
            sampling_rate=CONFIG.sample_rate,
            min_silence_duration_ms=CONFIG.barge_in_min_silence_ms,
            speech_pad_ms=CONFIG.barge_in_speech_pad_ms,
        )

        sd.play(audio_data, playback_sample_rate, blocking=False)
        start_time = time.monotonic()
        cooldown_until = start_time + (CONFIG.barge_in_cooldown_ms / 1000.0)

        try:
            with sd.InputStream(
                samplerate=CONFIG.sample_rate,
                channels=1,
                dtype="float32",
                blocksize=block_size,
            ) as mic_stream:
                while time.monotonic() - start_time < (duration_sec + 0.25):
                    raw_chunk, _ = mic_stream.read(block_size)
                    chunk = normalize_chunk(raw_chunk)

                    # Voorkom directe self-trigger door de speakers in de eerste ms.
                    if time.monotonic() < cooldown_until:
                        continue

                    if not passes_barge_in_energy_gate(chunk):
                        continue

                    event = barge_in_vad(torch.from_numpy(chunk), return_seconds=False)
                    if event and "start" in event and self._confirm_barge_in(mic_stream, block_size):
                        sd.stop()
                        barge_in_vad.reset_states()
                        return True

                    if event and "start" in event:
                        barge_in_vad.reset_states()
        finally:
            barge_in_vad.reset_states()

        return False

    @staticmethod
    def _confirm_barge_in(mic_stream: sd.InputStream, block_size: int) -> bool:
        """Bevestig barge-in met extra blokken zodat speaker-leak niet triggert."""
        confirm_hits = 0
        confirm_deadline = time.monotonic() + (CONFIG.barge_in_confirm_ms / 1000.0)
        while time.monotonic() < confirm_deadline:
            raw_chunk, _ = mic_stream.read(block_size)
            confirm_chunk = normalize_chunk(raw_chunk)

            if passes_barge_in_energy_gate(confirm_chunk):
                confirm_hits += 1
                if confirm_hits >= CONFIG.barge_in_confirm_hits:
                    return True
        return False

    def _capture_barge_in_transcript(self) -> str | None:
        """Neem na een onderbreking direct de nieuwe vraag op en transcribeer."""
        log("🎧 Onderbreking gedetecteerd. Spreek nu je nieuwe vraag in...")
        interruption_vad = VADIterator(
            self._vad_model,
            threshold=0.5,
            sampling_rate=CONFIG.sample_rate,
            min_silence_duration_ms=500,
            speech_pad_ms=180,
        )
        audio_segment = self._record_utterance(interruption_vad)
        return self._transcribe(audio_segment)

    # --- Chat-stream ------------------------------------------------------- #

    def _stream_chat_response(self, transcript: str) -> tuple[str, str | None]:
        """Stream het chat-antwoord en spreek het stuk voor stuk uit.

        Retourneert ``(volledige_tekst, onderbrekings_transcript)``. Het tweede
        element is gevuld wanneer de gebruiker het antwoord onderbrak.
        """
        full_text = ""
        speak_buffer = ""
        first_chunk_spoken = False

        try:
            with requests.post(
                CONFIG.chat_api_url,
                headers={
                    "Content-Type": "application/json",
                    "Accept": "text/event-stream",
                },
                json={"text": transcript},
                stream=True,
                timeout=(CONFIG.chat_connect_timeout_s, CONFIG.chat_read_timeout_s),
            ) as response:
                if response.status_code != 200:
                    log(f"❌ Chat API fout: {response.status_code} {response.text}")
                    return CONFIG.unavailable_text, None

                log("🌐 Stream ontvangen van chat endpoint...")
                log("🤖 Mistral antwoord: (streamend...)")
                for raw_line in response.iter_lines(decode_unicode=True):
                    chunk_text = self._parse_sse_line(raw_line)
                    if not chunk_text:
                        continue

                    full_text += chunk_text
                    speak_buffer += chunk_text

                    # Begin zo snel mogelijk te spreken bij de eerste bruikbare chunk.
                    if not first_chunk_spoken and speak_buffer.strip():
                        if len(speak_buffer.strip()) >= CONFIG.min_first_chunk_chars:
                            if self._speak(speak_buffer.strip()):
                                return full_text.strip(), self._capture_barge_in_transcript()
                            first_chunk_spoken = True
                            speak_buffer = ""
                            continue

                    ready_text, speak_buffer = pop_speakable_text(speak_buffer)
                    if ready_text and self._speak(ready_text):
                        return full_text.strip(), self._capture_barge_in_transcript()

                # Spreek resterende tekst uit als er geen eindpunctuatie was.
                if speak_buffer.strip() and self._speak(speak_buffer.strip()):
                    return full_text.strip(), self._capture_barge_in_transcript()

                spoken_text = full_text.strip() or CONFIG.unavailable_text
                return spoken_text, None

        except requests.RequestException:
            log("❌ Fout bij verbinding met chat API.")
            return CONFIG.unavailable_text, None

    @staticmethod
    def _parse_sse_line(raw_line: str | None) -> str:
        """Pak de tekstinhoud uit één SSE-regel (``data:``), of geef ``''``."""
        if not raw_line:
            return ""

        line = raw_line.strip()
        if not line.startswith("data:"):
            return ""

        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            return ""

        try:
            data_obj = json.loads(payload)
            return extract_stream_text(data_obj)
        except json.JSONDecodeError:
            # Sommige SSE-servers sturen platte tekst in `data:`-regels.
            return payload

    # --- Hoofdlus ---------------------------------------------------------- #

    def _handle_transcript(self, transcript: str) -> str | None:
        """Verwerk één transcript en retourneer een eventueel volgend transcript."""
        intent, prompt = detect_intent(transcript)

        if intent == "google_calendar":
            response = trigger_google_calendar(prompt)
            if self._speak(f"Bedankt voor je vraag. {response}"):
                return self._capture_barge_in_transcript()
            return None

        if intent == "google_gmail":
            response = trigger_google_gmail(prompt)
            if self._speak(f"Bedankt voor je vraag. {response}"):
                return self._capture_barge_in_transcript()
            return None

        chat_response_text, interrupted_transcript = self._stream_chat_response(transcript)
        if interrupted_transcript:
            return interrupted_transcript

        if chat_response_text == CONFIG.unavailable_text:
            self._speak(CONFIG.unavailable_text)
        else:
            log(f"🤖 Antwoord: {chat_response_text}")
        return None

    def run(self) -> None:
        """Voer de continue luister-, denk- en spreeklus uit tot Ctrl+C."""
        log("✅ Continue VAD-luistermodus actief. Stoppen met Ctrl+C.")
        log(f"🔧 Barge-in staat {'AAN' if CONFIG.enable_barge_in else 'UIT'}.")
        pending_transcript: str | None = None

        try:
            while True:
                if pending_transcript:
                    transcript: str | None = pending_transcript
                    pending_transcript = None
                    log(f"🗣️ Nieuwe vraag na onderbreking: {transcript}")
                else:
                    audio_segment = self._record_utterance(self._listen_vad)
                    transcript = self._transcribe(audio_segment)

                if not transcript:
                    self._speak("Sorry, ik kon je niet verstaan.")
                    continue

                pending_transcript = self._handle_transcript(transcript)
        except KeyboardInterrupt:
            log("\n🛑 Gestopt door gebruiker (Ctrl+C).")


def main() -> None:
    """Initialiseer de afhankelijkheden en start de spraakassistent."""
    log("Druk op Enter om te starten. Spreek na de bevestiging in het Nederlands.")
    input("Druk op Enter om te beginnen met luisteren...")

    client = Mistral(api_key=os.getenv("MISTRAL_API_KEY"))
    vad_model = load_silero_vad()

    assistant = VoiceAssistant(client=client, vad_model=vad_model)
    assistant.run()


if __name__ == "__main__":
    main()
