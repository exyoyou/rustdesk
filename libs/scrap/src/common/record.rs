use crate::CodecFormat;
#[cfg(feature = "hwcodec")]
use hbb_common::anyhow::anyhow;
use hbb_common::{
    bail, chrono, log,
    message_proto::{message, video_frame, EncodedVideoFrame, Message},
    ResultType,
};
#[cfg(feature = "hwcodec")]
use hwcodec::mux::{MuxContext, Muxer};
use std::{
    fs::{File, OpenOptions},
    io,
    ops::{Deref, DerefMut},
    path::PathBuf,
    sync::mpsc::Sender,
    time::Instant,
};
use webm::mux::{self, Segment, VideoTrack, Writer, AudioTrack, Track};

const MIN_SECS: u64 = 1;

#[derive(Debug, Clone)]
pub struct RecorderContext {
    pub server: bool,
    pub id: String,
    pub dir: String,
    pub display_idx: usize,
    pub camera: bool,
    pub tx: Option<Sender<RecordState>>,
}

#[derive(Debug, Clone)]
pub struct RecorderContext2 {
    pub filename: String,
    pub width: usize,
    pub height: usize,
    pub format: CodecFormat,
}

impl RecorderContext2 {
    pub fn set_filename(&mut self, ctx: &RecorderContext) -> ResultType<()> {
        if !PathBuf::from(&ctx.dir).exists() {
            std::fs::create_dir_all(&ctx.dir)?;
        }
        let file = if ctx.server { "incoming" } else { "outgoing" }.to_string()
            + "_"
            + &ctx.id.clone()
            + &chrono::Local::now().format("_%Y%m%d%H%M%S%3f_").to_string()
            + &format!(
                "{}{}_",
                if ctx.camera { "camera" } else { "display" },
                ctx.display_idx
            )
            + &self.format.to_string().to_lowercase()
            + if self.format == CodecFormat::VP9
                || self.format == CodecFormat::VP8
                || self.format == CodecFormat::AV1
            {
                ".webm"
            } else {
                ".mp4"
            };
        self.filename = PathBuf::from(&ctx.dir)
            .join(file)
            .to_string_lossy()
            .to_string();
        Ok(())
    }
}

unsafe impl Send for Recorder {}
unsafe impl Sync for Recorder {}

pub trait RecorderApi {
    fn new(ctx: RecorderContext, ctx2: RecorderContext2) -> ResultType<Self>
    where
        Self: Sized;
    fn write_video(&mut self, frame: &EncodedVideoFrame) -> bool;
    // pts in microseconds
    fn write_audio(&mut self, _data: &[u8], _pts_us: u64) -> bool {
        let _ = (_data, _pts_us);
        false
    }
}

#[derive(Debug)]
pub enum RecordState {
    NewFile(String),
    NewFrame,
    WriteTail,
    RemoveFile,
}

pub struct Recorder {
    pub inner: Option<Box<dyn RecorderApi>>,
    ctx: RecorderContext,
    ctx2: Option<RecorderContext2>,
    pts: Option<i64>,
    check_failed: bool,
    // audio timeline for recording (microseconds)
    audio_pts_us: u64,
    // assume Opus@48k stereo for remote audio unless specified in future
    audio_sample_rate: u32,
}

impl Deref for Recorder {
    type Target = Option<Box<dyn RecorderApi>>;

    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl DerefMut for Recorder {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl Recorder {
    pub fn new(ctx: RecorderContext) -> ResultType<Self> {
        Ok(Self {
            inner: None,
            ctx,
            ctx2: None,
            pts: None,
            check_failed: false,
            audio_pts_us: 0,
            audio_sample_rate: 48_000,
        })
    }

    fn check(&mut self, w: usize, h: usize, format: CodecFormat) -> ResultType<()> {
        match self.ctx2 {
            Some(ref ctx2) => {
                if ctx2.width != w || ctx2.height != h || ctx2.format != format {
                    let mut ctx2 = RecorderContext2 {
                        width: w,
                        height: h,
                        format,
                        filename: Default::default(),
                    };
                    ctx2.set_filename(&self.ctx)?;
                    self.ctx2 = Some(ctx2);
                    self.inner = None;
                }
            }
            None => {
                let mut ctx2 = RecorderContext2 {
                    width: w,
                    height: h,
                    format,
                    filename: Default::default(),
                };
                ctx2.set_filename(&self.ctx)?;
                self.ctx2 = Some(ctx2);
                self.inner = None;
            }
        }
        let Some(ctx2) = &self.ctx2 else {
            bail!("ctx2 is None");
        };
        if self.inner.is_none() {
            self.inner = match format {
                CodecFormat::VP8 | CodecFormat::VP9 | CodecFormat::AV1 => Some(Box::new(
                    WebmRecorder::new(self.ctx.clone(), (*ctx2).clone())?,
                )),
                #[cfg(feature = "hwcodec")]
                _ => Some(Box::new(HwRecorder::new(
                    self.ctx.clone(),
                    (*ctx2).clone(),
                )?)),
                #[cfg(not(feature = "hwcodec"))]
                _ => bail!("unsupported codec type"),
            };
            // pts is None when new inner is created
            self.pts = None;
            // reset audio timeline on new file
            self.audio_pts_us = 0;
            self.send_state(RecordState::NewFile(ctx2.filename.clone()));
        }
        Ok(())
    }

    pub fn write_message(&mut self, msg: &Message, w: usize, h: usize) {
        if let Some(message::Union::VideoFrame(vf)) = &msg.union {
            if let Some(frame) = &vf.union {
                self.write_frame(frame, w, h).ok();
            }
        }
    }

    pub fn write_frame(
        &mut self,
        frame: &video_frame::Union,
        w: usize,
        h: usize,
    ) -> ResultType<()> {
        if self.check_failed {
            bail!("check failed");
        }
        let format = CodecFormat::from(frame);
        if format == CodecFormat::Unknown {
            bail!("unsupported frame type");
        }
        let res = self.check(w, h, format);
        if res.is_err() {
            self.check_failed = true;
            log::error!("check failed: {:?}", res);
            res?;
        }
        match frame {
            video_frame::Union::Vp8s(vp8s) => {
                for f in vp8s.frames.iter() {
                    self.check_pts(f.pts, f.key, w, h, format)?;
                    self.as_mut().map(|x| x.write_video(f));
                }
            }
            video_frame::Union::Vp9s(vp9s) => {
                for f in vp9s.frames.iter() {
                    self.check_pts(f.pts, f.key, w, h, format)?;
                    self.as_mut().map(|x| x.write_video(f));
                }
            }
            video_frame::Union::Av1s(av1s) => {
                for f in av1s.frames.iter() {
                    self.check_pts(f.pts, f.key, w, h, format)?;
                    self.as_mut().map(|x| x.write_video(f));
                }
            }
            #[cfg(feature = "hwcodec")]
            video_frame::Union::H264s(h264s) => {
                for f in h264s.frames.iter() {
                    self.check_pts(f.pts, f.key, w, h, format)?;
                    self.as_mut().map(|x| x.write_video(f));
                }
            }
            #[cfg(feature = "hwcodec")]
            video_frame::Union::H265s(h265s) => {
                for f in h265s.frames.iter() {
                    self.check_pts(f.pts, f.key, w, h, format)?;
                    self.as_mut().map(|x| x.write_video(f));
                }
            }
            _ => bail!("unsupported frame type"),
        }
        self.send_state(RecordState::NewFrame);
        Ok(())
    }

    fn check_pts(
        &mut self,
        pts: i64,
        key: bool,
        w: usize,
        h: usize,
        format: CodecFormat,
    ) -> ResultType<()> {
        // https://stackoverflow.com/questions/76379101/how-to-create-one-playable-webm-file-from-two-different-video-tracks-with-same-c
        if self.pts.is_none() && !key {
            bail!("first frame is not key frame");
        }
        let old_pts = self.pts;
        self.pts = Some(pts);
        if old_pts.clone().unwrap_or_default() > pts {
            log::info!("pts {:?} -> {}, change record filename", old_pts, pts);
            self.inner = None;
            self.ctx2 = None;
            let res = self.check(w, h, format);
            if res.is_err() {
                self.check_failed = true;
                log::error!("check failed: {:?}", res);
                res?;
            }
            self.pts = Some(pts);
        }
        Ok(())
    }

    fn send_state(&self, state: RecordState) {
        self.ctx.tx.as_ref().map(|tx| tx.send(state));
    }

    /// Write an Opus packet into the recorder; timestamp is inferred as 20ms per packet by default.
    pub fn write_audio_opus(&mut self, data: &[u8]) {
        // guard
        if self.check_failed {
            return;
        }
        // do not write audio before the first video keyframe is accepted; wait for video timeline
        if self.pts.is_none() {
            return;
        }
        // lazy init guard
        if self.inner.is_none() {
            return;
        }
        // Align the first audio PTS to current video PTS (if available) to avoid early packets being rejected.
        if self.audio_pts_us == 0 {
            if let Some(v) = self.pts {
                if v > 0 {
                    // video pts is in milliseconds, convert to microseconds for audio timeline
                    self.audio_pts_us = (v as u64).saturating_mul(1_000);
                }
            }
        }
        // 精确按 Opus 包实际时长推进时间线，减少累计误差和拖尾
        let inc_us = opus_packet_duration_us(data, self.audio_sample_rate).unwrap_or(20_000);
        let mut pts_us = self.audio_pts_us;
        let video_us = (self.pts.unwrap_or_default() as u64).saturating_mul(1_000);
        // 若音频时间戳落后于当前视频时间线，则“快进”到不早于视频的最近边界，避免向 mux 发送全是“过去的帧”导致拒包
        if pts_us < video_us {
            let behind = video_us.saturating_sub(pts_us);
            let steps = (behind + inc_us - 1) / inc_us; // ceil(behind/inc_us)
            let new_pts = pts_us.saturating_add(steps.saturating_mul(inc_us));
            pts_us = new_pts;
            self.audio_pts_us = new_pts;
        }
        if let Some(inner) = self.as_mut() {
            let wrote = inner.write_audio(data, pts_us);
            // 推进策略：
            // 1) 若写入成功，正常推进音频时间线。
            // 2) 若失败，但推进后仍不超过视频时间线，则允许小步前进，避免因集群(timecode)边界/量化造成的“死等”。
            // 3) 若失败且推进会超前视频，则保持不动，等待视频追上。
            if wrote {
                self.audio_pts_us = self.audio_pts_us.saturating_add(inc_us);
                self.send_state(RecordState::NewFrame);
            } else {
                let next_audio_us = self.audio_pts_us.saturating_add(inc_us);
                if next_audio_us <= video_us {
                    self.audio_pts_us = next_audio_us;
                }
            }
        }
    }
}

/// 根据 Opus TOC 与包内帧数，估算该包的时长（微秒）。
/// 参考 libopus 的 opus_packet_get_samples_per_frame 与 opus_packet_get_nb_frames 实现。
fn opus_packet_duration_us(packet: &[u8], sample_rate: u32) -> Option<u64> {
    if packet.is_empty() || sample_rate == 0 { return None; }
    let toc = packet[0];
    let spf = opus_samples_per_frame(toc, sample_rate) as u64; // samples per frame
    let nbf = opus_nb_frames(packet)? as u64; // number of frames
    let samples = spf.saturating_mul(nbf);
    if samples == 0 { return None; }
    Some(samples.saturating_mul(1_000_000) / (sample_rate as u64))
}

#[inline]
fn opus_samples_per_frame(toc: u8, fs: u32) -> u32 {
    // 直接移植自 libopus：
    // if (toc & 0x80)      size = Fs/400;   // 2.5ms
    // else if ((toc & 0x60) == 0x60) size = Fs/50;   // 20ms
    // else if (toc & 0x60) size = Fs/100;  // 10ms
    // else                 size = Fs/200;  // 5ms
    if (toc & 0x80) != 0 {
        fs / 400
    } else if (toc & 0x60) == 0x60 {
        fs / 50
    } else if (toc & 0x60) != 0 {
        fs / 100
    } else {
        fs / 200
    }
}

#[inline]
fn opus_nb_frames(packet: &[u8]) -> Option<u32> {
    if packet.is_empty() { return None; }
    let toc = packet[0] & 0x03; // bottom two bits
    if toc == 0 { // one frame in the packet
        Some(1)
    } else if toc != 3 { // two frames
        Some(2)
    } else {
        // Code 3: N frames, count is in the next byte's lower 6 bits
        if packet.len() < 2 { return None; }
        let n = (packet[1] & 0x3F) as u32;
        // Guard against 0, which would stall PTS advancement; treat as at least 1 frame
        Some(n.max(1))
    }
}

struct WebmRecorder {
    vt: VideoTrack,
    webm: Option<Segment<Writer<File>>>,
    ctx: RecorderContext,
    ctx2: RecorderContext2,
    key: bool,
    written: bool,
    start: Instant,
    // optional audio track for Opus
    at: Option<AudioTrack>, // Opus audio track
    // track last pts (ns) per stream for proper finalize duration and diagnostics
    last_video_ns: u64,
    last_audio_ns: u64,
}

impl RecorderApi for WebmRecorder {
    fn new(ctx: RecorderContext, ctx2: RecorderContext2) -> ResultType<Self> {
        let out = match {
            OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&ctx2.filename)
        } {
            Ok(file) => file,
            Err(ref e) if e.kind() == io::ErrorKind::AlreadyExists => File::create(&ctx2.filename)?,
            Err(e) => return Err(e.into()),
        };
        let mut webm = match mux::Segment::new(mux::Writer::new(out)) {
            Some(v) => v,
            None => bail!("Failed to create webm mux"),
        };
        let vt = webm.add_video_track(
            ctx2.width as _,
            ctx2.height as _,
            None,
            if ctx2.format == CodecFormat::VP9 {
                mux::VideoCodecId::VP9
            } else if ctx2.format == CodecFormat::VP8 {
                mux::VideoCodecId::VP8
            } else {
                mux::VideoCodecId::AV1
            },
        );
        if ctx2.format == CodecFormat::AV1 {
            // [129, 8, 12, 0] in 3.6.0, but zero works
            let codec_private = vec![0, 0, 0, 0];
            if !webm.set_codec_private(vt.track_number(), &codec_private) {
                bail!("Failed to set codec private");
            }
        }
        // Add an Opus audio track (48k stereo) and attach minimal OpusHead for better compatibility.
        let mut at: Option<AudioTrack> = Some(webm.add_audio_track(48_000, 2, None, mux::AudioCodecId::Opus));
        if at.is_some() {
            fn opus_head_bytes(channels: u8, sample_rate: u32, pre_skip: u16, output_gain_q8: i16, mapping_family: u8) -> Vec<u8> {
                let mut v = Vec::with_capacity(19);
                v.extend_from_slice(b"OpusHead");
                v.push(1);
                v.push(channels);
                v.extend_from_slice(&pre_skip.to_le_bytes());
                v.extend_from_slice(&sample_rate.to_le_bytes());
                v.extend_from_slice(&output_gain_q8.to_le_bytes());
                v.push(mapping_family);
                v
            }
            let opus_head = opus_head_bytes(2, 48_000, 0, 0, 0);
            let mut set_ok = false;
            let vtn = vt.track_number();
            for cand in [vtn.saturating_add(1), vtn.saturating_add(2), vtn.saturating_add(3), vtn.saturating_add(4)] {
                if webm.set_codec_private(cand, &opus_head) {
                    set_ok = true;
                    break;
                }
            }
            if !set_ok {
                log::warn!("Unable to attach OpusHead CodecPrivate to audio track; some muxers may reject audio frames or players may not decode");
            }
        }
        Ok(WebmRecorder {
            vt,
            webm: Some(webm),
            ctx,
            ctx2,
            key: false,
            written: false,
            start: Instant::now(),
            at,
            last_video_ns: 0,
            last_audio_ns: 0,
        })
    }

    fn write_video(&mut self, frame: &EncodedVideoFrame) -> bool {
        if frame.key {
            self.key = true;
        }
        if self.key {
            let pts_ns = (frame.pts as u64).saturating_mul(1_000_000);
            let ok = self.vt.add_frame(&frame.data, pts_ns, frame.key);
            if ok {
                self.written = true;
                self.last_video_ns = pts_ns;
            }
            ok
        } else {
            false
        }
    }

    fn write_audio(&mut self, data: &[u8], pts_us: u64) -> bool {
        if let (Some(ref mut _webm), Some(ref mut at)) = (self.webm.as_mut(), self.at.as_mut()) {
            // For audio, key flag is always true
            // convert us -> ns as add_frame expects nanoseconds
            let pts_ns = pts_us.saturating_mul(1_000);
            // Mark audio blocks as non-key for safety
            let ok = at.add_frame(data, pts_ns, false);
            if ok {
                self.written = true;
                self.last_audio_ns = pts_ns;
            }
            ok
        } else {
            false
        }
    }
}

impl Drop for WebmRecorder {
    fn drop(&mut self) {
        // Provide best-effort duration to help players show progress bar
        let duration_ns = self.last_video_ns.max(self.last_audio_ns);
        let finalize_ok = std::mem::replace(&mut self.webm, None)
            .map_or(false, |webm| webm.finalize(Some(duration_ns)));
        let mut state = RecordState::WriteTail;
        let should_remove = !self.written || self.start.elapsed().as_secs() < MIN_SECS;
        if should_remove {
            std::fs::remove_file(&self.ctx2.filename).ok();
            state = RecordState::RemoveFile;
        }
        self.ctx.tx.as_ref().map(|tx| tx.send(state));
    }
}

#[cfg(feature = "hwcodec")]
struct HwRecorder {
    muxer: Option<Muxer>,
    ctx: RecorderContext,
    ctx2: RecorderContext2,
    written: bool,
    key: bool,
    start: Instant,
}

#[cfg(feature = "hwcodec")]
impl RecorderApi for HwRecorder {
    fn new(ctx: RecorderContext, ctx2: RecorderContext2) -> ResultType<Self> {
        let muxer = Muxer::new(MuxContext {
            filename: ctx2.filename.clone(),
            width: ctx2.width,
            height: ctx2.height,
            is265: ctx2.format == CodecFormat::H265,
            framerate: crate::hwcodec::DEFAULT_FPS as _,
        })
        .map_err(|_| anyhow!("Failed to create hardware muxer"))?;
        Ok(HwRecorder {
            muxer: Some(muxer),
            ctx,
            ctx2,
            written: false,
            key: false,
            start: Instant::now(),
        })
    }

    fn write_video(&mut self, frame: &EncodedVideoFrame) -> bool {
        if frame.key {
            self.key = true;
        }
        if self.key {
            let ok = self
                .muxer
                .as_mut()
                .map(|m| m.write_video(&frame.data, frame.key).is_ok())
                .unwrap_or_default();
            if ok {
                self.written = true;
            }
            ok
        } else {
            false
        }
    }
}

#[cfg(feature = "hwcodec")]
impl Drop for HwRecorder {
    fn drop(&mut self) {
        self.muxer.as_mut().map(|m| m.write_tail().ok());
        let mut state = RecordState::WriteTail;
        if !self.written || self.start.elapsed().as_secs() < MIN_SECS {
            // The process cannot access the file because it is being used by another process
            self.muxer = None;
            std::fs::remove_file(&self.ctx2.filename).ok();
            state = RecordState::RemoveFile;
        }
        self.ctx.tx.as_ref().map(|tx| tx.send(state));
    }
}
