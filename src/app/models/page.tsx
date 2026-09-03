import { redirect } from "next/navigation";

export default function ModelsRedirect() {
  redirect("/settings?tab=" + encodeURIComponent("模型管理"));
}
