import org.springframework.ai.openai.OpenAiChatModel;
import java.lang.reflect.Constructor;
public class CheckConstructors {
    public static void main(String[] args) {
        for (Constructor<?> c : OpenAiChatModel.class.getConstructors()) {
            System.out.println(c);
        }
    }
}
