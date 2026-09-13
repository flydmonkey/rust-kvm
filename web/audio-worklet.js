// 播放端，跑在音频渲染线程上。主线程把一块块 Int16 LE 的 PCM postMessage 过来，
// 这里解成 Float32 塞进环形缓冲，process() 每次取走固定的一批帧。
//
// 为什么要环形缓冲而不是一个队列：process() 每次要的是固定长度（通常 128 帧），
// 而网络送来的块是 10ms（48kHz 下 480 帧）且边界和它不对齐，用队列就得反复
// 切割块头块尾。定长环省掉这些边界处理。
//
// 时钟不同源是这里所有复杂度的来源：采集时钟在采集卡上，播放时钟在本机声卡上，
// 两者永远差那么一点点，长跑必然漂移。所以：
//   - 攒够 target 才开播，否则一上来就断断续续
//   - 欠载时输出静音，并退回「重新攒」状态，而不是每块都断一下
//   - 缓冲涨过 max 就丢到 target，用一次轻微的咔哒换「延迟不会无限累积」
//
// 起播那一刻还要把多出来的水位裁掉。浏览器启动音频设备要一两百毫秒，这段时间里
// process() 一次都不会被调用，而网络还在往缓冲里灌——不裁的话这段积压就变成了
// 永久的延迟（实测停在 270ms，而目标是 100ms），而且只有涨过 max 才会被清理。
//
// target 还会随欠载自己往上爬。固定水位在远程链路上走不通：抖动一旦超过水位就
// 欠载，而欠载后重新攒又回到同一个水位，于是一直咔咔响。让它每欠载一次抬高一档，
// 链路差的时候自己找到能稳住的水位，代价只是多一点延迟。

class PcmPlayer extends AudioWorkletProcessor {
    constructor(options) {
        super();
        const opts = options.processorOptions;
        this.channels = opts.channels;
        this.target = opts.targetFrames;
        this.grow = opts.growFrames;
        this.max = opts.maxFrames;
        // 目标水位别涨到把 max 顶穿，否则一起播就触发「超上限」的裁剪
        this.maxTarget = Math.floor(opts.maxFrames / 2);
        // 环要比 max 大一截，否则刚到上限就开始覆盖还没播的数据
        this.capacity = opts.maxFrames * 2;

        this.ring = [];
        for (let c = 0; c < this.channels; c++) {
            this.ring.push(new Float32Array(this.capacity));
        }
        this.read = 0;
        this.write = 0;
        this.available = 0;

        this.playing = false;
        this.underruns = 0;
        this.resyncs = 0;
        this.sinceReport = 0;

        this.port.onmessage = (event) => this._push(event.data);
    }

    // pcm 是交错排列的 Int16Array：帧 0 的各声道、帧 1 的各声道……
    _push(pcm) {
        const frames = Math.floor(pcm.length / this.channels);
        for (let f = 0; f < frames; f++) {
            const base = f * this.channels;
            for (let c = 0; c < this.channels; c++) {
                // 除 32768 而不是 32767：负半轴满量程是 -32768，
                // 用 32767 会让最响的那一格越界到 -1.0000305
                this.ring[c][this.write] = pcm[base + c] / 32768;
            }
            this.write = (this.write + 1) % this.capacity;
        }
        this.available += frames;

        if (this.available > this.max) {
            this._trimTo(this.target);
            this.resyncs += 1;
        }
    }

    // 丢掉最旧的，把水位降到 frames。丢的是已经到手但还没播的声音，
    // 听着是一下咔哒——但总比让延迟一直挂在那儿好。
    _trimTo(frames) {
        if (this.available <= frames) {
            return;
        }
        const drop = this.available - frames;
        this.read = (this.read + drop) % this.capacity;
        this.available -= drop;
    }

    process(_inputs, outputs) {
        const out = outputs[0];
        const need = out[0].length;

        if (!this.playing) {
            if (this.available < this.target) {
                this._maybeReport(need);
                return true;
            }
            // 起播前裁到目标水位：设备启动那段时间攒下的积压不该变成永久延迟
            this._trimTo(this.target);
            this.playing = true;
        }

        if (this.available < need) {
            // 退回攒水位，别在欠载边缘反复进出，那样是连续的断续声
            this.playing = false;
            this.underruns += 1;
            // 这条链路撑不住当前水位，抬高一档再试
            this.target = Math.min(this.maxTarget, this.target + this.grow);
            this._maybeReport(need);
            return true;
        }

        for (let c = 0; c < out.length; c++) {
            // 输出声道比源多时（比如源是单声道）就复用最后一个源声道
            const src = this.ring[Math.min(c, this.channels - 1)];
            const dst = out[c];
            let r = this.read;
            for (let i = 0; i < need; i++) {
                dst[i] = src[r];
                r = r + 1 === this.capacity ? 0 : r + 1;
            }
        }
        this.read = (this.read + need) % this.capacity;
        this.available -= need;

        this._maybeReport(need);
        return true;
    }

    // 每秒往主线程报一次水位，给叠加层显示用
    _maybeReport(frames) {
        this.sinceReport += frames;
        if (this.sinceReport < sampleRate) {
            return;
        }
        this.sinceReport = 0;
        this.port.postMessage({
            bufferedMs: (this.available / sampleRate) * 1000,
            targetMs: (this.target / sampleRate) * 1000,
            playing: this.playing,
            underruns: this.underruns,
            resyncs: this.resyncs
        });
    }
}

registerProcessor("pcm-player", PcmPlayer);
