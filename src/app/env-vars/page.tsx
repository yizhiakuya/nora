import { redirect } from "next/navigation";

export default function EnvVarsRedirect() {
  redirect("/environments?tab=" + encodeURIComponent("环境变量"));
}
