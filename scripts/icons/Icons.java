import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * whereMI 아이콘 생성기 (JDK 만 있으면 됨, 외부 라이브러리 없음)
 *
 *   java -Djava.awt.headless=true scripts/icons/Icons.java <프로젝트 루트>
 *
 * 컨셉: "다음 역까지 남은 시간"
 *   - 12시 방향(출발)에서 시계 방향으로 차오르는 진행 링 = 지나온 시간
 *   - 링 끝의 점 = 지금 달리는 열차
 *   - 옅은 나머지 링 = 남은 구간
 *   - 가운데 = 지하철 앞모습
 *
 * 생성물
 *   app/src/main/res/mipmap-*dpi/ic_launcher_foreground.png   적응형 아이콘 전경 (흰 그림)
 *   app/src/main/res/mipmap-*dpi/ic_launcher_monochrome.png   Android 13+ 테마 아이콘
 *   app/src/main/res/drawable-*dpi/ic_stat_ride.png           상태바 / 빠른 설정 타일 (흰 단색)
 *   docs/icons/*.png                                           미리보기
 */
public class Icons {
    static final Color LINE4 = new Color(0x00, 0xA5, 0xDE);
    static final String[] DPI = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    static final double[] SCALE = {1, 1.5, 2, 3, 4};

    /** 단위 정사각형(0..1) 기준 그림을 size px 캔버스의 (ox, oy, s) 영역에 흰색으로 그린다. */
    static void glyph(Graphics2D g, double ox, double oy, double s, boolean withTrain, float trackAlpha) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double cx = ox + 0.5 * s, cy = oy + 0.5 * s, r = 0.34 * s;
        float stroke = (float) (0.085 * s);
        double elapsed = 240; // 지나온 각도

        // 남은 구간 (옅은 링)
        if (trackAlpha > 0) {
            g.setComposite(AlphaComposite.SrcOver.derive(trackAlpha));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 90 - elapsed, -(360 - elapsed), Arc2D.OPEN));
            g.setComposite(AlphaComposite.SrcOver);
        }
        // 지나온 구간 (진한 링)
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 90, -elapsed, Arc2D.OPEN));

        // 열차 점 (링 끝)
        double a = Math.toRadians(90 - elapsed);
        double dx = cx + r * Math.cos(a), dy = cy - r * Math.sin(a), dr = 0.078 * s;
        g.fill(new Ellipse2D.Double(dx - dr, dy - dr, 2 * dr, 2 * dr));

        if (withTrain) {
            // 지하철 앞모습: 몸통(흰색) - 창문/전조등은 투명하게 파냄
            g.fill(new RoundRectangle2D.Double(ox + 0.385 * s, oy + 0.365 * s, 0.23 * s, 0.275 * s, 0.11 * s, 0.11 * s));
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.Clear);
            g.fill(new RoundRectangle2D.Double(ox + 0.415 * s, oy + 0.395 * s, 0.17 * s, 0.095 * s, 0.05 * s, 0.05 * s));
            double lr = 0.02 * s;
            g.fill(new Ellipse2D.Double(ox + 0.445 * s - lr, oy + 0.585 * s - lr, 2 * lr, 2 * lr));
            g.fill(new Ellipse2D.Double(ox + 0.555 * s - lr, oy + 0.585 * s - lr, 2 * lr, 2 * lr));
            g.setComposite(old);
        }
    }

    static BufferedImage canvas(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    /** 스쿼클 경로 (초타원, 전체 모양) */
    static Shape squircle(double x, double y, double size, double n) {
        Path2D p = new Path2D.Double();
        double c = size / 2, e = 2 / n;
        for (int i = 0; i <= 360; i++) {
            double t = Math.toRadians(i), ct = Math.cos(t), st = Math.sin(t);
            double px = x + c + c * Math.signum(ct) * Math.pow(Math.abs(ct), e);
            double py = y + c + c * Math.signum(st) * Math.pow(Math.abs(st), e);
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        return p;
    }

    /** 완성된 앱 아이콘 (배경 + 그림) — 미리보기용 */
    static BufferedImage appIcon(int size) {
        BufferedImage bg = canvas(size, size);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(LINE4);
        g.fill(squircle(0, 0, size, 5));
        BufferedImage fg = canvas(size, size);
        Graphics2D f = fg.createGraphics();
        glyph(f, size * 0.12, size * 0.12, size * 0.76, true, 0.38f);
        f.dispose();
        g.drawImage(fg, 0, 0, null);
        g.dispose();
        return bg;
    }

    static void save(BufferedImage img, File f) throws Exception {
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("  " + f);
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        File res = new File(root, "app/src/main/res");

        // 1) 적응형 아이콘 전경: 108dp 캔버스, 그림은 안전 영역(가운데 66dp) 안
        for (int i = 0; i < DPI.length; i++) {
            int px = (int) Math.round(108 * SCALE[i]);
            double inner = px * 66.0 / 108.0, off = (px - inner) / 2;
            BufferedImage fg = canvas(px, px);
            Graphics2D g = fg.createGraphics();
            glyph(g, off, off, inner, true, 0.38f);
            g.dispose();
            save(fg, new File(res, "mipmap-" + DPI[i] + "/ic_launcher_foreground.png"));

            BufferedImage mono = canvas(px, px);
            g = mono.createGraphics();
            glyph(g, off, off, inner, true, 0.38f);
            g.dispose();
            save(mono, new File(res, "mipmap-" + DPI[i] + "/ic_launcher_monochrome.png"));
        }

        // 2) 상태바 / 타일 아이콘: 24dp, 흰 단색. 작게 보이므로 링을 꽉 채우고 열차 그림 포함
        for (int i = 0; i < DPI.length; i++) {
            int px = (int) Math.round(24 * SCALE[i]);
            BufferedImage st = canvas(px, px);
            Graphics2D g = st.createGraphics();
            glyph(g, -px * 0.08, -px * 0.08, px * 1.16, true, 0.45f);
            g.dispose();
            save(st, new File(res, "drawable-" + DPI[i] + "/ic_stat_ride.png"));
        }

        // 3) 미리보기
        File docs = new File(root, "docs/icons");
        save(appIcon(512), new File(docs, "app_icon_512.png"));

        // 미리보기 시트: 앱 아이콘 여러 크기 + 어두운 상태바 위 아이콘
        int W = 900, H = 420;
        BufferedImage sheet = canvas(W, H);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xF2F2F5));
        g.fillRect(0, 0, W, H);
        int x = 40;
        for (int s : new int[]{192, 128, 96, 64, 48}) {
            g.drawImage(appIcon(s), x, 40 + (192 - s), null);
            x += s + 36;
        }
        // 어두운 상태바 흉내
        g.setColor(new Color(0x1E2A33));
        g.fillRect(0, 290, W, 130);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 44));
        g.drawString("3:52", 40, 372);
        for (int[] spec : new int[][]{{48, 160}, {72, 240}, {96, 350}}) {
            int s = spec[0];
            BufferedImage st = canvas(s, s);
            Graphics2D sg = st.createGraphics();
            glyph(sg, -s * 0.08, -s * 0.08, s * 1.16, true, 0.45f);
            sg.dispose();
            g.drawImage(st, spec[1], 355 - s / 2 - 4, null);
        }
        g.dispose();
        save(sheet, new File(docs, "preview_sheet.png"));
    }
}
