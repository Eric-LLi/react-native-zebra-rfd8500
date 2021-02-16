require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-zebra-rfd8500"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.description  = <<-DESC
                  react-native-zebra-rfd8500
                   DESC
  s.homepage     = "https://github.com/Eric-LLi/react-native-zebra-rfd8500"
  # brief license entry:
  s.license      = "MIT"
  # optional - use expanded license entry instead:
  # s.license    = { :type => "MIT", :file => "LICENSE" }
  s.authors      = { "yaoweili" => "ericli1116@gmail.com" }
  s.platforms    = { :ios => "9.0" }
  s.source       = { :git => "https://github.com/Eric-LLi/react-native-zebra-rfd8500.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,c,m,swift}"
  s.requires_arc = true
  s.ios.vendored_library = 'ios/symbolrfid-sdk/libZebraSdk.a'
  s.ios.framework = 'ExternalAccessory', 'CoreBluetooth'
  s.dependency "React"
  # ...
  # s.dependency "..."
end

