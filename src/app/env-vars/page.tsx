import { redirect } from "next/navigation";

export default function EnvVarsRedirect() {
  redirect("/settings?tab=" + encodeURIComponent("环境变量"));
}
