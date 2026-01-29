resource "aws_lb" "loadtest_lb" {
  name = "grafana-alb"
  load_balancer_type = "application"
  subnets = module.vpc.public_subnets
  security_groups = [aws_security_group.alb_sg.id]
}

resource "aws_lb_target_group" "loadtest_lb_tg" {
  name = "grafana-tg"
  port = 3000
  protocol = "HTTP"
  vpc_id = module.vpc.vpc_id
  target_type = "ip"

  health_check {
    path = "/login"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.loadtest_lb.arn
  port = 80
  protocol = "HTTP"

  default_action {
    type = "forward"
    target_group_arn = aws_lb_target_group.loadtest_lb_tg.arn
  }
}
