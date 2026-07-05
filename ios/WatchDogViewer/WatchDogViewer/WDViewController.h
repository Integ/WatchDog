#import <UIKit/UIKit.h>

@interface WDViewController : UIViewController <UITextFieldDelegate>

- (void)pausePlayback;
- (void)resumePlaybackIfNeeded;

@end
