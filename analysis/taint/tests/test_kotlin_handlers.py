"""gen_taint_config.parse_handlers reads the request handlers of a controller from `javap -p -v`
output: channel-extractor parses Java sources only, so a Kotlin controller is found in its bytecode."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
import gen_taint_config as g  # noqa: E402

JAVAP = '''Classfile /C:/build/classes/com/shop/ProxyController.class
public class com.shop.ProxyController
  minor version: 0
Constant pool:
   #28 = Utf8               Lorg/springframework/web/bind/annotation/GetMapping;
  #100 = Utf8               (Ljava/lang/String;)Ljava/lang/String;
{
  public com.shop.ProxyController(com.shop.FetchService);
    descriptor: (Lcom/shop/FetchService;)V
    flags: (0x0001) ACC_PUBLIC
    Code:
      stack=2, locals=2, args_size=2
    RuntimeInvisibleParameterAnnotations:
      parameter 0:
        0: #8()
          org.jetbrains.annotations.NotNull

  public java.lang.String fetch(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;
    flags: (0x0001) ACC_PUBLIC
    Code:
      stack=2, locals=2, args_size=2
      LineNumberTable:
        line 21: 0
    RuntimeVisibleAnnotations:
      0: #28(#29=[s#30])
        org.springframework.web.bind.annotation.GetMapping(
          value=["/fetch"]
        )
    RuntimeVisibleParameterAnnotations:
      parameter 0:
        0: #31()
          org.springframework.web.bind.annotation.RequestParam

  private final java.lang.String helper(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;
    flags: (0x0012) ACC_PRIVATE, ACC_FINAL
}
SourceFile: "ProxyController.kt"
RuntimeVisibleAnnotations:
  0: #100()
    org.springframework.web.bind.annotation.RestController
  1: #101(#29=[s#102])
    org.springframework.web.bind.annotation.RequestMapping(
      value=["/api/proxy"]
    )
  2: #103()
    kotlin.Metadata(
    )
'''


class KotlinHandlerTest(unittest.TestCase):

    def test_mapped_methods_of_a_controller_are_handlers(self):
        self.assertEqual(g.parse_handlers(JAVAP),
                         [('com.shop.ProxyController', ('fetch', ['java.lang.String'], 'java.lang.String', False))])

    def test_a_class_that_is_not_a_controller_has_no_handlers(self):
        text = JAVAP.replace('org.springframework.web.bind.annotation.RestController',
                             'org.springframework.stereotype.Service')
        self.assertEqual(g.parse_handlers(text), [])


if __name__ == '__main__':
    unittest.main()
