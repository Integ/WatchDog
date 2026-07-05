#import "WDViewController.h"

#import <MediaPlayer/MediaPlayer.h>
#import <QuartzCore/QuartzCore.h>

static NSString * const WDLastURLDefaultsKey = @"WDLastRTSPURL";
static NSString * const WDDefaultRTSPURL = @"rtsp://192.168.1.50:8554/video";

@interface WDViewController ()

@property (strong, nonatomic) UIView *playerHostView;
@property (strong, nonatomic) UIView *toolbarView;
@property (strong, nonatomic) UITextField *urlField;
@property (strong, nonatomic) UIButton *playButton;
@property (strong, nonatomic) UIButton *stopButton;
@property (strong, nonatomic) UILabel *statusLabel;
@property (strong, nonatomic) UILabel *emptyLabel;
@property (strong, nonatomic) MPMoviePlayerController *moviePlayer;
@property (assign, nonatomic) BOOL shouldResumeAfterActive;

@end

@implementation WDViewController

- (void)viewDidLoad
{
    [super viewDidLoad];

    self.view.backgroundColor = [UIColor blackColor];
    [[UIApplication sharedApplication] setStatusBarHidden:YES withAnimation:UIStatusBarAnimationNone];

    self.playerHostView = [[UIView alloc] initWithFrame:CGRectZero];
    self.playerHostView.backgroundColor = [UIColor blackColor];
    self.playerHostView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.view addSubview:self.playerHostView];

    self.emptyLabel = [[UILabel alloc] initWithFrame:CGRectZero];
    self.emptyLabel.backgroundColor = [UIColor clearColor];
    self.emptyLabel.textColor = [UIColor colorWithWhite:0.72f alpha:1.0f];
    self.emptyLabel.font = [UIFont boldSystemFontOfSize:22.0f];
    self.emptyLabel.textAlignment = UITextAlignmentCenter;
    self.emptyLabel.text = @"WatchDog Viewer";
    [self.playerHostView addSubview:self.emptyLabel];

    self.toolbarView = [[UIView alloc] initWithFrame:CGRectZero];
    self.toolbarView.backgroundColor = [UIColor colorWithWhite:0.0f alpha:0.78f];
    self.toolbarView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleBottomMargin;
    [self.view addSubview:self.toolbarView];

    self.urlField = [[UITextField alloc] initWithFrame:CGRectZero];
    self.urlField.borderStyle = UITextBorderStyleRoundedRect;
    self.urlField.clearButtonMode = UITextFieldViewModeWhileEditing;
    self.urlField.autocapitalizationType = UITextAutocapitalizationTypeNone;
    self.urlField.autocorrectionType = UITextAutocorrectionTypeNo;
    self.urlField.keyboardType = UIKeyboardTypeURL;
    self.urlField.returnKeyType = UIReturnKeyGo;
    self.urlField.delegate = self;
    self.urlField.text = [self savedURLString];
    [self.toolbarView addSubview:self.urlField];

    self.playButton = [UIButton buttonWithType:UIButtonTypeRoundedRect];
    [self.playButton setTitle:@"Connect" forState:UIControlStateNormal];
    [self.playButton addTarget:self action:@selector(playButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    [self.toolbarView addSubview:self.playButton];

    self.stopButton = [UIButton buttonWithType:UIButtonTypeRoundedRect];
    [self.stopButton setTitle:@"Stop" forState:UIControlStateNormal];
    [self.stopButton addTarget:self action:@selector(stopButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.stopButton.enabled = NO;
    [self.toolbarView addSubview:self.stopButton];

    self.statusLabel = [[UILabel alloc] initWithFrame:CGRectZero];
    self.statusLabel.backgroundColor = [UIColor clearColor];
    self.statusLabel.textColor = [UIColor whiteColor];
    self.statusLabel.font = [UIFont systemFontOfSize:13.0f];
    self.statusLabel.lineBreakMode = UILineBreakModeTailTruncation;
    self.statusLabel.text = @"Enter the WatchDog RTSP URL, then connect.";
    [self.toolbarView addSubview:self.statusLabel];

    [self registerMovieNotifications];
}

- (void)viewDidUnload
{
    [self stopPlayback];
    [self unregisterMovieNotifications];
    [super viewDidUnload];
}

- (void)dealloc
{
    [self stopPlayback];
    [self unregisterMovieNotifications];
}

- (void)viewDidLayoutSubviews
{
    [super viewDidLayoutSubviews];

    CGRect bounds = self.view.bounds;
    CGFloat toolbarHeight = 64.0f;
    self.toolbarView.frame = CGRectMake(0.0f, 0.0f, bounds.size.width, toolbarHeight);
    self.playerHostView.frame = CGRectMake(0.0f, toolbarHeight, bounds.size.width, bounds.size.height - toolbarHeight);
    self.emptyLabel.frame = self.playerHostView.bounds;

    CGFloat padding = 10.0f;
    CGFloat buttonWidth = 82.0f;
    CGFloat buttonHeight = 36.0f;
    CGFloat statusHeight = 18.0f;
    CGFloat fieldWidth = bounds.size.width - padding * 4.0f - buttonWidth * 2.0f;

    self.urlField.frame = CGRectMake(padding, 10.0f, fieldWidth, buttonHeight);
    self.playButton.frame = CGRectMake(CGRectGetMaxX(self.urlField.frame) + padding, 10.0f, buttonWidth, buttonHeight);
    self.stopButton.frame = CGRectMake(CGRectGetMaxX(self.playButton.frame) + padding, 10.0f, buttonWidth, buttonHeight);
    self.statusLabel.frame = CGRectMake(padding, 45.0f, bounds.size.width - padding * 2.0f, statusHeight);
    self.moviePlayer.view.frame = self.playerHostView.bounds;
}

- (BOOL)shouldAutorotateToInterfaceOrientation:(UIInterfaceOrientation)interfaceOrientation
{
    return UIInterfaceOrientationIsLandscape(interfaceOrientation);
}

- (BOOL)shouldAutorotate
{
    return YES;
}

- (NSUInteger)supportedInterfaceOrientations
{
    return UIInterfaceOrientationMaskLandscape;
}

- (void)playButtonTapped:(id)sender
{
    [self startPlaybackFromField];
}

- (void)stopButtonTapped:(id)sender
{
    [self stopPlayback];
}

- (BOOL)textFieldShouldReturn:(UITextField *)textField
{
    [textField resignFirstResponder];
    [self startPlaybackFromField];
    return YES;
}

- (void)startPlaybackFromField
{
    NSString *urlString = [self.urlField.text stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if ([urlString length] == 0) {
        [self updateStatus:@"Enter an RTSP URL first."];
        return;
    }

    NSURL *url = [NSURL URLWithString:urlString];
    if (url == nil || [[url scheme] length] == 0) {
        [self updateStatus:@"The URL is not valid."];
        return;
    }

    [[NSUserDefaults standardUserDefaults] setObject:urlString forKey:WDLastURLDefaultsKey];
    [[NSUserDefaults standardUserDefaults] synchronize];

    [self startPlaybackWithURL:url];
}

- (void)startPlaybackWithURL:(NSURL *)url
{
    [self stopPlaybackWithoutStatus];

    self.emptyLabel.hidden = YES;
    self.moviePlayer = [[MPMoviePlayerController alloc] initWithContentURL:url];
    self.moviePlayer.controlStyle = MPMovieControlStyleNone;
    self.moviePlayer.scalingMode = MPMovieScalingModeAspectFit;
    self.moviePlayer.shouldAutoplay = YES;
    self.moviePlayer.useApplicationAudioSession = NO;
    self.moviePlayer.view.frame = self.playerHostView.bounds;
    self.moviePlayer.view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.playerHostView addSubview:self.moviePlayer.view];

    self.playButton.enabled = NO;
    self.stopButton.enabled = YES;
    [self updateStatus:[NSString stringWithFormat:@"Connecting to %@...", [url absoluteString]]];

    [UIApplication sharedApplication].idleTimerDisabled = YES;
    [self.moviePlayer prepareToPlay];
    [self.moviePlayer play];
}

- (void)stopPlayback
{
    [self stopPlaybackWithoutStatus];
    [self updateStatus:@"Stopped."];
}

- (void)stopPlaybackWithoutStatus
{
    [UIApplication sharedApplication].idleTimerDisabled = NO;
    self.shouldResumeAfterActive = NO;

    if (self.moviePlayer != nil) {
        [self.moviePlayer stop];
        [self.moviePlayer.view removeFromSuperview];
        self.moviePlayer = nil;
    }

    self.emptyLabel.hidden = NO;
    self.playButton.enabled = YES;
    self.stopButton.enabled = NO;
}

- (void)pausePlayback
{
    if (self.moviePlayer.playbackState == MPMoviePlaybackStatePlaying) {
        self.shouldResumeAfterActive = YES;
        [self.moviePlayer pause];
        [self updateStatus:@"Paused while the app is inactive."];
    }
}

- (void)resumePlaybackIfNeeded
{
    if (self.shouldResumeAfterActive && self.moviePlayer != nil) {
        self.shouldResumeAfterActive = NO;
        [self.moviePlayer play];
        [self updateStatus:@"Resuming stream..."];
    }
}

- (NSString *)savedURLString
{
    NSString *urlString = [[NSUserDefaults standardUserDefaults] stringForKey:WDLastURLDefaultsKey];
    if ([urlString length] > 0) {
        return urlString;
    }
    return WDDefaultRTSPURL;
}

- (void)updateStatus:(NSString *)status
{
    self.statusLabel.text = status;
}

- (void)registerMovieNotifications
{
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    [center addObserver:self selector:@selector(loadStateDidChange:) name:MPMoviePlayerLoadStateDidChangeNotification object:nil];
    [center addObserver:self selector:@selector(playbackStateDidChange:) name:MPMoviePlayerPlaybackStateDidChangeNotification object:nil];
    [center addObserver:self selector:@selector(playbackDidFinish:) name:MPMoviePlayerPlaybackDidFinishNotification object:nil];
}

- (void)unregisterMovieNotifications
{
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)loadStateDidChange:(NSNotification *)notification
{
    if (notification.object != self.moviePlayer) {
        return;
    }

    MPMovieLoadState state = self.moviePlayer.loadState;
    if ((state & MPMovieLoadStatePlayable) == MPMovieLoadStatePlayable) {
        [self updateStatus:@"Live stream is playable."];
    } else if ((state & MPMovieLoadStateStalled) == MPMovieLoadStateStalled) {
        [self updateStatus:@"Stream stalled. Check Wi-Fi and WatchDog status."];
    }
}

- (void)playbackStateDidChange:(NSNotification *)notification
{
    if (notification.object != self.moviePlayer) {
        return;
    }

    switch (self.moviePlayer.playbackState) {
        case MPMoviePlaybackStatePlaying:
            [self updateStatus:@"Playing."];
            break;
        case MPMoviePlaybackStatePaused:
            [self updateStatus:@"Paused."];
            break;
        case MPMoviePlaybackStateInterrupted:
            [self updateStatus:@"Playback interrupted."];
            break;
        case MPMoviePlaybackStateSeekingForward:
        case MPMoviePlaybackStateSeekingBackward:
            [self updateStatus:@"Buffering..."];
            break;
        case MPMoviePlaybackStateStopped:
        default:
            break;
    }
}

- (void)playbackDidFinish:(NSNotification *)notification
{
    if (notification.object != self.moviePlayer) {
        return;
    }

    NSDictionary *userInfo = [notification userInfo];
    NSNumber *reason = [userInfo objectForKey:MPMoviePlayerPlaybackDidFinishReasonUserInfoKey];
    if ([reason integerValue] == MPMovieFinishReasonPlaybackError) {
        [self updateStatus:@"Playback failed. This iOS build may not support this RTSP stream."];
    } else {
        [self updateStatus:@"Playback finished."];
    }

    [UIApplication sharedApplication].idleTimerDisabled = NO;
    self.playButton.enabled = YES;
    self.stopButton.enabled = NO;
}

@end
