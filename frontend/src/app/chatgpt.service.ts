import { Injectable } from "@angular/core";

@Injectable({
  providedIn: "root",
})
export class ChatGptService {
  private readonly apiKey = "";
  async getOptimalComputeUnit(prompt: string): Promise<{ uid: number; explanation: string }> {
    try {
      const response = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify({
          model: "gpt-4o",
          messages: [
            { role: "system", content: "You are a scheduling assistant for Texera workflows." },
            { role: "user", content: prompt },
          ],
        }),
      });

      if (!response.ok) {
        const errorBody = await response.json();
        throw new Error(`OpenAI API error: ${JSON.stringify(errorBody)}`);
      }

      const result = await response.json();
      const content = result.choices?.[0]?.message?.content?.trim() ?? "";

      const [uidLine, ...explanationLines] = content
        .split("\n")
        .map((line: string) => line.trim())
        .filter(Boolean);
      const uid = parseInt(uidLine, 10);
      const explanation = explanationLines.join(" ");

      if (isNaN(uid)) {
        throw new Error(`Invalid UID received: "${uidLine}" from: "${content}"`);
      }

      return { uid, explanation };
    } catch (error) {
      console.error("ChatGPT fetch error:", error);
      throw error;
    }
  }
}
