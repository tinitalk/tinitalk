type SinkMedia = HTMLMediaElement & { setSinkId?: (id: string) => Promise<void> };

export class CallAudioPlayback {
  private selection?: Promise<void>;
  private closed = false;

  constructor(private media: HTMLMediaElement) {}

  async attach(stream: MediaStream): Promise<void> {
    if (this.closed) return;
    // Play the supplied original WebRTC tracks directly. The call supplies only
    // audio here so a reserved video track cannot hold up voice playback.
    if (this.media.srcObject !== stream) this.media.srcObject = stream;
    await this.resume();
  }

  setOutputDevice(id: string): Promise<void> {
    if (this.closed) return Promise.resolve();
    // Coalesce overlapping taps instead of invoking another switch later,
    // outside the user gesture that requested it.
    if (this.selection) return this.selection;
    const operation = this.switchOutput(id);
    this.selection = operation;
    void operation.then(() => { this.selection = undefined; }, () => { this.selection = undefined; });
    return operation;
  }

  private async switchOutput(id: string): Promise<void> {
    const sink = this.media as SinkMedia;
    if (!sink.setSinkId) throw new DOMException('Audio output selection is unavailable', 'NotSupportedError');
    // Reach the native API directly in the output button's gesture.
    await sink.setSinkId(id);
    if (this.closed || !this.media.srcObject) return;
    // Keep the original stream attached throughout native output selection.
    if (this.media.paused) await this.resume();
  }

  async resume(): Promise<void> {
    if (!this.closed && this.media.srcObject) await this.media.play();
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.media.pause();
    this.media.srcObject = null;
    // The peer connection owns remote tracks; retain the element's selected sink.
  }
}
