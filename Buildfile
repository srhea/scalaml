require 'buildr/scala'
repositories.remote << 'http://www.ibiblio.org/maven2'
define 'scalaml', :version => '0.1-SNAPSHOT' do
  package :jar
end
