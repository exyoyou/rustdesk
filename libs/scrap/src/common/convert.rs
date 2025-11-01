#![allow(non_camel_case_types)]
#![allow(non_snake_case)]
#![allow(non_upper_case_globals)]
#![allow(improper_ctypes)]
#![allow(dead_code)]

include!(concat!(env!("OUT_DIR"), "/yuv_ffi.rs"));

#[cfg(not(target_os = "ios"))]
use crate::PixelBuffer;
use crate::{generate_call_macro, EncodeYuvFormat, TraitPixelBuffer};
use hbb_common::{bail, log, ResultType};

generate_call_macro!(call_yuv, false);

#[cfg(target_os = "android")]
pub fn android420_to_i420(
    src_y: *const u8,
    src_stride_y: i32,
    src_u: *const u8,
    src_stride_u: i32,
    src_v: *const u8,
    src_stride_v: i32,
    src_pixel_stride_uv: i32,
    dst_y: *mut u8,
    dst_stride_y: i32,
    dst_u: *mut u8,
    dst_stride_u: i32,
    dst_v: *mut u8,
    dst_stride_v: i32,
    width: i32,
    height: i32,
) -> hbb_common::ResultType<()> {
    // Safety: pointers and strides are provided by JNI from direct buffers; libyuv validates args.
    call_yuv!(Android420ToI420(
        src_y,
        src_stride_y,
        src_u,
        src_stride_u,
        src_v,
        src_stride_v,
        src_pixel_stride_uv,
        dst_y,
        dst_stride_y,
        dst_u,
        dst_stride_u,
        dst_v,
        dst_stride_v,
        width,
        height,
    ));
    Ok(())
}

#[cfg(not(target_os = "ios"))]
pub fn convert_to_yuv(
    captured: &PixelBuffer,
    dst_fmt: EncodeYuvFormat,
    dst: &mut Vec<u8>,
    mid_data: &mut Vec<u8>,
) -> ResultType<()> {
    let src = captured.data();
    let src_stride = captured.stride();
    let src_pixfmt = captured.pixfmt();
    let src_width = captured.width();
    let src_height = captured.height();
    if src_width > dst_fmt.w || src_height > dst_fmt.h {
        bail!(
            "src rect > dst rect: ({src_width}, {src_height}) > ({},{})",
            dst_fmt.w,
            dst_fmt.h
        );
    }
    if src_pixfmt == crate::Pixfmt::BGRA
        || src_pixfmt == crate::Pixfmt::RGBA
        || src_pixfmt == crate::Pixfmt::RGB565LE
    {
        // stride is calculated, not real, so we need to check it
        if src_stride[0] < src_width * src_pixfmt.bytes_per_pixel() {
            bail!(
                "src_stride too small: {} < {}",
                src_stride[0],
                src_width * src_pixfmt.bytes_per_pixel()
            );
        }
        if src.len() < src_stride[0] * src_height {
            bail!(
                "wrong src len, {} < {} * {}",
                src.len(),
                src_stride[0],
                src_height
            );
        }
    }
    let align = |x: usize| (x + 63) / 64 * 64;
    let unsupported = format!(
        "unsupported pixfmt conversion: {src_pixfmt:?} -> {:?}",
        dst_fmt.pixfmt
    );

    match (src_pixfmt, dst_fmt.pixfmt) {
        // Fast-path: I420 source to I420 destination (plane copy with possible stride differences)
        (crate::Pixfmt::I420, crate::Pixfmt::I420) => {
            let dst_stride_y = dst_fmt.stride[0];
            let dst_stride_uv = dst_fmt.stride[1];
            // Allocate enough to safely address Y + U + V using dst_fmt.u/v offsets.
            // Keep behavior consistent with existing I420 RGB paths using slight over-allocation for safety.
            dst.resize(dst_fmt.h * dst_stride_y * 2, 0);

            let w = src_width;
            let h = src_height;
            let cw = w / 2;
            let ch = h / 2;

            // Source is compact I420 per PixelBuffer::new_i420: stride_y = w, stride_u/v = w/2
            let src_y_stride = w;
            let src_uv_stride = cw;
            let src_y = &src[..w * h];
            let src_u = &src[w * h..w * h + cw * ch];
            let src_v = &src[w * h + cw * ch..w * h + cw * ch * 2];

            // Dest planes
            let (dst_y_off, dst_u_off, dst_v_off) = (0, dst_fmt.u, dst_fmt.v);

            // Copy Y plane row by row respecting destination stride
            for j in 0..h {
                let src_row = &src_y[j * src_y_stride..j * src_y_stride + w];
                let dst_row = &mut dst[dst_y_off + j * dst_stride_y..dst_y_off + j * dst_stride_y + w];
                dst_row.copy_from_slice(src_row);
            }
            // Copy U plane
            for j in 0..ch {
                let src_row = &src_u[j * src_uv_stride..j * src_uv_stride + cw];
                let base = dst_u_off + j * dst_stride_uv;
                let dst_row = &mut dst[base..base + cw];
                dst_row.copy_from_slice(src_row);
            }
            // Copy V plane
            for j in 0..ch {
                let src_row = &src_v[j * src_uv_stride..j * src_uv_stride + cw];
                let base = dst_v_off + j * dst_stride_uv;
                let dst_row = &mut dst[base..base + cw];
                dst_row.copy_from_slice(src_row);
            }
        }
        // I420 source to NV12 destination (interleave UV)
        (crate::Pixfmt::I420, crate::Pixfmt::NV12) => {
            let dst_stride_y = dst_fmt.stride[0];
            let dst_stride_uv = dst_fmt.stride[1];
            // Allocate with alignment similar to existing NV12 RGB paths
            let align = |x: usize| (x + 63) / 64 * 64;
            dst.resize(align(dst_fmt.h) * (align(dst_stride_y) + align(dst_stride_uv)), 0);

            let w = src_width;
            let h = src_height;
            let cw = w / 2;
            let ch = h / 2;

            // Source compact I420 planes
            let src_y_stride = w;
            let src_uv_stride = cw;
            let src_y = &src[..w * h];
            let src_u = &src[w * h..w * h + cw * ch];
            let src_v = &src[w * h + cw * ch..w * h + cw * ch * 2];

            // Dest planes: Y then interleaved UV at offset u
            let (dst_y_off, dst_uv_off) = (0, dst_fmt.u);

            // Copy Y plane
            for j in 0..h {
                let src_row = &src_y[j * src_y_stride..j * src_y_stride + w];
                let dst_row = &mut dst[dst_y_off + j * dst_stride_y..dst_y_off + j * dst_stride_y + w];
                dst_row.copy_from_slice(src_row);
            }
            // Interleave U and V into UV plane
            for j in 0..ch {
                let src_u_row = &src_u[j * src_uv_stride..j * src_uv_stride + cw];
                let src_v_row = &src_v[j * src_uv_stride..j * src_uv_stride + cw];
                let mut di = dst_uv_off + j * dst_stride_uv;
                for i in 0..cw {
                    dst[di] = src_u_row[i];
                    dst[di + 1] = src_v_row[i];
                    di += 2;
                }
            }
        }
        // I420 source to I444 destination
        (crate::Pixfmt::I420, crate::Pixfmt::I444) => {
            let dst_stride_y = dst_fmt.stride[0];
            let dst_stride_u = dst_fmt.stride[1];
            let dst_stride_v = dst_fmt.stride[2];
            dst.resize(
                align(dst_fmt.h)
                    * (align(dst_stride_y) + align(dst_stride_u) + align(dst_stride_v)),
                0,
            );

            let w = src_width;
            let h = src_height;
            let cw = w / 2;
            let ch = h / 2;

            // Source compact I420 planes
            let src_y = &src[..w * h];
            let src_u = &src[w * h..w * h + cw * ch];
            let src_v = &src[w * h + cw * ch..w * h + cw * ch * 2];

            let dst_y = dst.as_mut_ptr();
            let dst_u = dst[dst_fmt.u..].as_mut_ptr();
            let dst_v = dst[dst_fmt.v..].as_mut_ptr();

            call_yuv!(I420ToI444(
                src_y.as_ptr(),
                w as _,
                src_u.as_ptr(),
                cw as _,
                src_v.as_ptr(),
                cw as _,
                dst_y,
                dst_stride_y as _,
                dst_u,
                dst_stride_u as _,
                dst_v,
                dst_stride_v as _,
                w as _,
                h as _,
            ));
        }
        (crate::Pixfmt::BGRA, crate::Pixfmt::I420)
        | (crate::Pixfmt::RGBA, crate::Pixfmt::I420)
        | (crate::Pixfmt::RGB565LE, crate::Pixfmt::I420) => {
            let dst_stride_y = dst_fmt.stride[0];
            let dst_stride_uv = dst_fmt.stride[1];
            dst.resize(dst_fmt.h * dst_stride_y * 2, 0); // waste some memory to ensure memory safety
            let dst_y = dst.as_mut_ptr();
            let dst_u = dst[dst_fmt.u..].as_mut_ptr();
            let dst_v = dst[dst_fmt.v..].as_mut_ptr();
            let f = match src_pixfmt {
                crate::Pixfmt::BGRA => ARGBToI420,
                crate::Pixfmt::RGBA => ABGRToI420,
                crate::Pixfmt::RGB565LE => RGB565ToI420,
                _ => bail!(unsupported),
            };
            call_yuv!(f(
                src.as_ptr(),
                src_stride[0] as _,
                dst_y,
                dst_stride_y as _,
                dst_u,
                dst_stride_uv as _,
                dst_v,
                dst_stride_uv as _,
                src_width as _,
                src_height as _,
            ));
        }
        (crate::Pixfmt::BGRA, crate::Pixfmt::NV12)
        | (crate::Pixfmt::RGBA, crate::Pixfmt::NV12)
        | (crate::Pixfmt::RGB565LE, crate::Pixfmt::NV12) => {
            let dst_stride_y = dst_fmt.stride[0];
            let dst_stride_uv = dst_fmt.stride[1];
            dst.resize(
                align(dst_fmt.h) * (align(dst_stride_y) + align(dst_stride_uv / 2)),
                0,
            );
            let dst_y = dst.as_mut_ptr();
            let dst_uv = dst[dst_fmt.u..].as_mut_ptr();
            let (input, input_stride) = match src_pixfmt {
                crate::Pixfmt::BGRA => (src.as_ptr(), src_stride[0]),
                crate::Pixfmt::RGBA => (src.as_ptr(), src_stride[0]),
                crate::Pixfmt::RGB565LE => {
                    let mid_stride = src_width * 4;
                    mid_data.resize(mid_stride * src_height, 0);
                    call_yuv!(RGB565ToARGB(
                        src.as_ptr(),
                        src_stride[0] as _,
                        mid_data.as_mut_ptr(),
                        mid_stride as _,
                        src_width as _,
                        src_height as _,
                    ));
                    (mid_data.as_ptr(), mid_stride)
                }
                _ => bail!(unsupported),
            };
            let f = match src_pixfmt {
                crate::Pixfmt::BGRA => ARGBToNV12,
                crate::Pixfmt::RGBA => ABGRToNV12,
                crate::Pixfmt::RGB565LE => ARGBToNV12,
                _ => bail!(unsupported),
            };
            call_yuv!(f(
                input,
                input_stride as _,
                dst_y,
                dst_stride_y as _,
                dst_uv,
                dst_stride_uv as _,
                src_width as _,
                src_height as _,
            ));
        }
        (crate::Pixfmt::BGRA, crate::Pixfmt::I444)
        | (crate::Pixfmt::RGBA, crate::Pixfmt::I444)
        | (crate::Pixfmt::RGB565LE, crate::Pixfmt::I444) => {
            let dst_stride_y = dst_fmt.stride[0];
            let dst_stride_u = dst_fmt.stride[1];
            let dst_stride_v = dst_fmt.stride[2];
            dst.resize(
                align(dst_fmt.h)
                    * (align(dst_stride_y) + align(dst_stride_u) + align(dst_stride_v)),
                0,
            );
            let dst_y = dst.as_mut_ptr();
            let dst_u = dst[dst_fmt.u..].as_mut_ptr();
            let dst_v = dst[dst_fmt.v..].as_mut_ptr();
            let (input, input_stride) = match src_pixfmt {
                crate::Pixfmt::BGRA => (src.as_ptr(), src_stride[0]),
                crate::Pixfmt::RGBA => {
                    mid_data.resize(src.len(), 0);
                    call_yuv!(ABGRToARGB(
                        src.as_ptr(),
                        src_stride[0] as _,
                        mid_data.as_mut_ptr(),
                        src_stride[0] as _,
                        src_width as _,
                        src_height as _,
                    ));
                    (mid_data.as_ptr(), src_stride[0])
                }
                crate::Pixfmt::RGB565LE => {
                    let mid_stride = src_width * 4;
                    mid_data.resize(mid_stride * src_height, 0);
                    call_yuv!(RGB565ToARGB(
                        src.as_ptr(),
                        src_stride[0] as _,
                        mid_data.as_mut_ptr(),
                        mid_stride as _,
                        src_width as _,
                        src_height as _,
                    ));
                    (mid_data.as_ptr(), mid_stride)
                }
                _ => bail!(unsupported),
            };

            call_yuv!(ARGBToI444(
                input,
                input_stride as _,
                dst_y,
                dst_stride_y as _,
                dst_u,
                dst_stride_u as _,
                dst_v,
                dst_stride_v as _,
                src_width as _,
                src_height as _,
            ));
        }
        _ => {
            bail!(unsupported);
        }
    }
    Ok(())
}

#[cfg(not(target_os = "ios"))]
pub fn convert(captured: &PixelBuffer, pixfmt: crate::Pixfmt, dst: &mut Vec<u8>) -> ResultType<()> {
    if captured.pixfmt() == pixfmt {
        dst.extend_from_slice(captured.data());
        return Ok(());
    }

    let src = captured.data();
    let src_stride = captured.stride();
    let src_pixfmt = captured.pixfmt();
    let src_width = captured.width();
    let src_height = captured.height();

    let unsupported = format!(
        "unsupported pixfmt conversion: {src_pixfmt:?} -> {:?}",
        pixfmt
    );

    match (src_pixfmt, pixfmt) {
        (crate::Pixfmt::BGRA, crate::Pixfmt::RGBA) | (crate::Pixfmt::RGBA, crate::Pixfmt::BGRA) => {
            dst.resize(src.len(), 0);
            call_yuv!(ABGRToARGB(
                src.as_ptr(),
                src_stride[0] as _,
                dst.as_mut_ptr(),
                src_stride[0] as _,
                src_width as _,
                src_height as _,
            ));
        }
        _ => {
            bail!(unsupported);
        }
    }
    Ok(())
}
