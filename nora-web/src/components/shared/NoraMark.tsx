/**
 * Nora 品牌标(N 字母标,2026-09-30):
 * 替代此前到处用的 Sparkles 星星图标(用户反馈「太 AI 味」)。
 * - 默认彩色版(logo-mark.svg):浅色/深色背景均可,用于导航与品牌位
 * - white 变体(logo-mark-white.svg):彩色底上用(如聊天头像的蓝色圆底)
 */
export function NoraMark({ className, white = false }: { className?: string; white?: boolean }) {
  return <img src={white ? "/logo-mark-white.svg" : "/logo-mark.svg"} alt="" aria-hidden className={className} />;
}
